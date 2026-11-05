package com.lumen.finance.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.finance.dto.portal.MerchantDtos.DisputeRequest;
import com.lumen.finance.dto.portal.MerchantDtos.DisputeResult;
import com.lumen.finance.entity.BillingAdjustment;
import com.lumen.finance.entity.BillingInvoice;
import com.lumen.finance.entity.BillingMonthlyClosing;
import com.lumen.finance.entity.BillingPaymentRecord;
import com.lumen.finance.entity.BillingReconciliation;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.*;
import com.lumen.finance.service.impl.MerchantBillingViewServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MerchantBillingViewService — 对账异议")
class MerchantBillingViewServiceDisputeTest {

    @Mock BillingInvoiceMapper invoiceMapper;
    @Mock BillingReconciliationMapper reconMapper;
    @Mock BillingPaymentRecordMapper paymentMapper;
    @Mock BillingAdjustmentMapper adjustmentMapper;
    @Mock BillingMonthlyClosingMapper closingMapper;
    @Mock AdjustmentService adjustmentService;

    @InjectMocks MerchantBillingViewServiceImpl service;

    private static final Long TENANT = 1L;
    private static final Long ME = 42L;
    private static final Long USER = 7L;

    /**
     * MyBatis-Plus 的 lambda 列名缓存由 Spring 启动时的 {@code TableInfoHelper} 填充;
     * 纯 Mockito 单测没有 Spring 上下文, 缓存是空的, 于是任何
     * {@code eq(Entity::getX, v)} 在生成 SQL 片段时都会抛
     * {@code MybatisPlusException: can not find lambda cache for this entity}。
     *
     * <p>本测试类会自己跑 {@code reconScope} / {@code invoiceScope} (它们内部就是一堆
     * lambda 谓词), 所以必须自己补这一步 —— 否则用例在进入断言之前就炸, 变成一个
     * 什么都不证明的红。与 {@code MerchantBillingViewServiceTest} 里同名的那段是同一件事
     * (TableInfoHelper 的缓存是 static 的, 重复初始化无害)。
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : List.of(BillingInvoice.class, BillingReconciliation.class,
                BillingPaymentRecord.class, BillingAdjustment.class, BillingMonthlyClosing.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    /**
     * 合法长度的异议理由 (≥ {@code ADJUSTMENT_REASON_MIN_LEN} = 10)。
     *
     * <p>长度是硬闸门: 理由太短撑不起一条要进 L1/L2 审批的异议, 商家也等于没把话说完。
     */
    private static final String REASON = "9 月 3 日那票没送到";

    /** 一张可异议的对账单: 已 ADJUSTMENT_CREATED + 挂了 DRAFT 调账单 31。 */
    private BillingReconciliation openRecon() {
        BillingReconciliation r = new BillingReconciliation();
        r.setId(11L);
        r.setTenantId(TENANT);
        r.setCounterpartyId(ME);
        r.setPeriod("2026-09");
        r.setDirection("RECEIVABLE");
        r.setCurrency("CNY");
        r.setVarianceAmount(new BigDecimal("-10.0000"));
        r.setAdjustmentId(31L);
        r.setReconciliationStatus("ADJUSTMENT_CREATED");
        return r;
    }

    /** 该期已闭期的月结记录 —— 异议闸门放行的前提。 */
    private BillingMonthlyClosing closedClosing() {
        BillingMonthlyClosing c = new BillingMonthlyClosing();
        c.setTenantId(TENANT);
        c.setPeriod("2026-09");
        c.setStatus("CLOSED");
        return c;
    }

    private static BillingAdjustment adjustment(String status) {
        BillingAdjustment a = new BillingAdjustment();
        a.setId(31L);
        a.setTenantId(TENANT);
        a.setStatus(status);
        return a;
    }

    /** 条件更新命中 1 行 (正常路径)。 */
    private void stubUpdateHitsOneRow() {
        when(reconMapper.update(isNull(), any())).thenReturn(1);
    }

    /**
     * 断言异常码 <b>与</b>消息片段 —— 只断言码的话, 几个同为 409_005 的拒绝分支
     * 无法区分 (可能全都掉进同一个分支而测试照样绿)。
     */
    private static void assertFinanceError(Throwable t, FinanceErrorCode code, String messageFragment) {
        assertThat(t).isInstanceOf(FinanceException.class);
        FinanceException ex = (FinanceException) t;
        assertThat(ex.getErrorCode()).isEqualTo(code);
        assertThat(ex.getMessage()).as("消息片段要能区分同码的不同拒绝分支").contains(messageFragment);
    }

    @Test
    @DisplayName("对账单不存在 → 404_006")
    void raiseDispute_unknownRecon() {
        when(reconMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, null, REASON)))
                .isInstanceOf(FinanceException.class)
                .extracting(e -> ((FinanceException) e).getErrorCode())
                .isEqualTo(FinanceErrorCode.RECONCILIATION_NOT_FOUND);
    }

    /**
     * <b>闭期方向</b>: 只有该期<b>已 CLOSED</b> 才可提异议 (spec §7.2 step 2)。
     * 期次还在 OPEN / IN_PROGRESS 时数字还会变, 此时记下来的主张没有意义。
     *
     * <p>早期版本把这条闸门写反了 (未闭期才能提), 整条链路跟着错了一轮 ——
     * 这个用例就是钉住正确方向的那颗钉子。
     */
    @Test
    @DisplayName("期间未闭期 (无月结记录) → 409_005, 且不碰调账单")
    void raiseDispute_periodNotClosedRejected() {
        when(reconMapper.selectOne(any())).thenReturn(openRecon());
        when(closingMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, null, REASON)))
                .satisfies(e -> assertFinanceError(e, FinanceErrorCode.RECON_NOT_DISPUTABLE, "not CLOSED"));
        verify(adjustmentService, never()).get(any(), any());
        verify(adjustmentService, never()).submit(any(), any(), any());
        verify(reconMapper, never()).update(isNull(), any());
    }

    @Test
    @DisplayName("期间已 CLOSED → 放行到下一步 (闭期闸门本身不拦)")
    void raiseDispute_closedPeriodPassesTheGate() {
        when(reconMapper.selectOne(any())).thenReturn(openRecon());
        when(closingMapper.selectOne(any())).thenReturn(closedClosing());

        // 理由太短 → 说明已经过了闭期闸门, 卡在了下一道闸门上 (理由闸门在调账单闸门之前)
        assertThatThrownBy(() -> service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, null, "太短")))
                .satisfies(e -> assertFinanceError(e, FinanceErrorCode.ADJUSTMENT_REASON_REQUIRED,
                        "at least 10 characters"));
    }

    /**
     * <b>状态闸门</b>: 财务 resolve / waive 之后 recon 状态会变成
     * {@code RESOLVED} / {@code WAIVED}, 而 {@code adjustment_id} 还留在行上。
     * 只卡 adjustment_id 非空的话, 商家能把一笔已经"认账并豁免"的差异顶回 L1 审批。
     */
    @ParameterizedTest(name = "recon 状态 {0} → 409_005")
    @ValueSource(strings = {"WAIVED", "RESOLVED", "PENDING", "MATCHED"})
    @DisplayName("recon 状态非 ADJUSTMENT_CREATED → 409_005")
    void raiseDispute_statusNotAdjustmentCreatedRejected(String status) {
        BillingReconciliation r = openRecon();
        r.setReconciliationStatus(status);
        when(reconMapper.selectOne(any())).thenReturn(r);
        when(closingMapper.selectOne(any())).thenReturn(closedClosing());

        assertThatThrownBy(() -> service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, null, REASON)))
                .satisfies(e -> assertFinanceError(e, FinanceErrorCode.RECON_NOT_DISPUTABLE,
                        "not disputable"));
        verify(adjustmentService, never()).get(any(), any());
        verify(reconMapper, never()).update(isNull(), any());
    }

    @Test
    @DisplayName("没有关联调账单 (adjustment_id 为空) → 409_005, 无法推进审批")
    void raiseDispute_noAdjustmentLinked() {
        BillingReconciliation r = openRecon();
        r.setAdjustmentId(null);
        when(reconMapper.selectOne(any())).thenReturn(r);
        when(closingMapper.selectOne(any())).thenReturn(closedClosing());

        assertThatThrownBy(() -> service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, null, REASON)))
                .satisfies(e -> assertFinanceError(e, FinanceErrorCode.RECON_NOT_DISPUTABLE,
                        "not disputable"));
        verify(adjustmentService, never()).get(any(), any());
        verify(reconMapper, never()).update(isNull(), any());
    }

    @Test
    @DisplayName("已提过异议 → 409_005, 且不碰调账单")
    void raiseDispute_alreadyDisputed() {
        BillingReconciliation r = openRecon();
        r.setDisputeAt(LocalDateTime.of(2026, 10, 1, 8, 0));
        when(reconMapper.selectOne(any())).thenReturn(r);
        when(closingMapper.selectOne(any())).thenReturn(closedClosing());

        assertThatThrownBy(() -> service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, null, REASON)))
                .satisfies(e -> assertFinanceError(e, FinanceErrorCode.RECON_NOT_DISPUTABLE,
                        "already disputed"));
        verify(adjustmentService, never()).submit(any(), any(), any());
        verify(reconMapper, never()).update(isNull(), any());
    }

    @Test
    @DisplayName("理由为空 → 400_012 (ADJUSTMENT_REASON_REQUIRED), 且不碰调账单")
    void raiseDispute_blankReasonRejected() {
        when(reconMapper.selectOne(any())).thenReturn(openRecon());
        when(closingMapper.selectOne(any())).thenReturn(closedClosing());

        assertThatThrownBy(() -> service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, null, "   ")))
                .satisfies(e -> assertFinanceError(e, FinanceErrorCode.ADJUSTMENT_REASON_REQUIRED,
                        "at least 10 characters"));
        verify(adjustmentService, never()).submit(any(), any(), any());
        verify(reconMapper, never()).update(isNull(), any());
    }

    @Test
    @DisplayName("理由 9 字 (差 1 字) → 400_012")
    void raiseDispute_reasonTooShortRejected() {
        when(reconMapper.selectOne(any())).thenReturn(openRecon());
        when(closingMapper.selectOne(any())).thenReturn(closedClosing());

        assertThatThrownBy(() -> service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, null, "理".repeat(9))))
                .satisfies(e -> assertFinanceError(e, FinanceErrorCode.ADJUSTMENT_REASON_REQUIRED,
                        "at least 10 characters"));
    }

    @Test
    @DisplayName("主张金额为 0 → 400_022 (BAD_REQUEST), 负数则放行")
    void raiseDispute_zeroClaimRejected() {
        when(reconMapper.selectOne(any())).thenReturn(openRecon());
        when(closingMapper.selectOne(any())).thenReturn(closedClosing());

        assertThatThrownBy(() -> service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, new BigDecimal("0.0000"), REASON)))
                .satisfies(e -> assertFinanceError(e, FinanceErrorCode.BAD_REQUEST,
                        "must not be zero"));
        verify(reconMapper, never()).update(isNull(), any());
    }

    /**
     * {@code adjustment_id} 悬空 (指向一张已被删或根本不存在的调账单) 时,
     * {@code AdjustmentService.get} 抛的是 {@code ADJUSTMENT_NOT_FOUND} ——
     * 但从商家视角看, "关联调账单不存在" 就是"没有可推进的关联调账单",
     * 与 {@code adjustment_id} 为空同一性质, 必须统一映射成 {@code RECON_NOT_DISPUTABLE},
     * 否则会漏出 400_015 这个内部码, 与本接口声明的契约不符。
     */
    @Test
    @DisplayName("关联调账单取不到 → 409_005 (不漏出 400_015)")
    void raiseDispute_danglingAdjustmentSurfacesAsNotDisputable() {
        when(reconMapper.selectOne(any())).thenReturn(openRecon());
        when(closingMapper.selectOne(any())).thenReturn(closedClosing());
        when(adjustmentService.get(TENANT, 31L))
                .thenThrow(new FinanceException(FinanceErrorCode.ADJUSTMENT_NOT_FOUND,
                        "Adjustment 31 not found"));

        assertThatThrownBy(() -> service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, null, REASON)))
                .satisfies(e -> assertFinanceError(e, FinanceErrorCode.RECON_NOT_DISPUTABLE,
                        "not available"));
        verify(reconMapper, never()).update(isNull(), any());
    }

    /**
     * 调账单已经走完 (REJECTED / L2_APPROVED / POSTED) → 不可异议。
     */
    @ParameterizedTest(name = "调账单状态 {0} → 409_005")
    @ValueSource(strings = {"REJECTED", "L2_APPROVED", "POSTED"})
    @DisplayName("调账单状态不可用 → 409_005, 且不 submit")
    void raiseDispute_unusableAdjustmentStatusRejected(String status) {
        when(reconMapper.selectOne(any())).thenReturn(openRecon());
        when(closingMapper.selectOne(any())).thenReturn(closedClosing());
        when(adjustmentService.get(TENANT, 31L)).thenReturn(adjustment(status));

        assertThatThrownBy(() -> service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, null, REASON)))
                .satisfies(e -> assertFinanceError(e, FinanceErrorCode.RECON_NOT_DISPUTABLE,
                        "cannot be disputed"));
        verify(adjustmentService, never()).submit(any(), any(), any());
        verify(reconMapper, never()).update(isNull(), any());
    }

    /**
     * <b>submit 边界</b> (spec §7.2 step 7): 调账单已经是 {@code PENDING_*} 说明
     * staff 已经手工 submit 过了 —— 商家这时候才提出异议是完全正常的场景,
     * 不该被拒, 也不该重复 submit (重复 submit 会被状态机挡回 409)。
     */
    @Test
    @DisplayName("调账单已是 PENDING_L1 → 不重复 submit, 仍写三列并回当前状态")
    void raiseDispute_alreadyPendingDoesNotResubmit() {
        when(reconMapper.selectOne(any())).thenReturn(openRecon());
        when(closingMapper.selectOne(any())).thenReturn(closedClosing());
        when(adjustmentService.get(TENANT, 31L)).thenReturn(adjustment("PENDING_L1"));
        stubUpdateHitsOneRow();

        DisputeResult out = service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, new BigDecimal("-12.00"), REASON));

        assertThat(out.adjustmentStatus()).isEqualTo("PENDING_L1");
        verify(adjustmentService, never()).submit(any(), any(), any());
        verify(reconMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("成功路径: 写三列 + submit 推进 DRAFT→PENDING_L1, 不新建调账单")
    void raiseDispute_writesColumnsAndSubmitsExistingAdjustment() {
        when(reconMapper.selectOne(any())).thenReturn(openRecon());
        when(closingMapper.selectOne(any())).thenReturn(closedClosing());
        when(adjustmentService.get(TENANT, 31L)).thenReturn(adjustment("DRAFT"));
        when(adjustmentService.submit(TENANT, 31L, USER)).thenReturn(adjustment("PENDING_L1"));
        stubUpdateHitsOneRow();

        DisputeResult out = service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, new BigDecimal("-12.00"), REASON));

        assertThat(out.reconId()).isEqualTo(11L);
        assertThat(out.adjustmentId()).isEqualTo(31L);
        assertThat(out.adjustmentStatus()).isEqualTo("PENDING_L1");
        assertThat(out.disputeAt()).isNotNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<BillingReconciliation>> c =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(reconMapper).update(isNull(), c.capture());
        LambdaUpdateWrapper<BillingReconciliation> w = c.getValue();
        assertThat(w.getSqlSet())
                .contains("dispute_amount=").contains("dispute_reason=").contains("dispute_at=");
        // 负数主张是合法的 —— 商家主张的是差额
        assertThat(w.getParamNameValuePairs().values())
                .contains(new BigDecimal("-12.00"), REASON);
    }

    /**
     * 三列必须写成<b>带 CAS 谓词的条件更新</b>。
     *
     * <p>{@code t_reconciliation} 没有 {@code version} 列, {@code BillingAdjustment}
     * 也没有 {@code @Version} —— 所以 {@code updateById} 没有任何乐观锁, "本期是否
     * 已提过" 只能靠 {@code dispute_at IS NULL} 判断, 而"先查后写"不是原子的:
     * 两个并发请求都读到 NULL、都通过校验, 后写的把先写的理由覆盖掉。
     */
    @Test
    @DisplayName("写三列必须是带 CAS 谓词的条件更新 (dispute_at IS NULL + 归属 + 软删过滤)")
    void raiseDispute_writeIsAtomicConditionalUpdate() {
        when(reconMapper.selectOne(any())).thenReturn(openRecon());
        when(closingMapper.selectOne(any())).thenReturn(closedClosing());
        when(adjustmentService.get(TENANT, 31L)).thenReturn(adjustment("DRAFT"));
        when(adjustmentService.submit(TENANT, 31L, USER)).thenReturn(adjustment("PENDING_L1"));
        stubUpdateHitsOneRow();

        service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, new BigDecimal("-12.00"), REASON));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<BillingReconciliation>> c =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(reconMapper).update(isNull(), c.capture());
        LambdaUpdateWrapper<BillingReconciliation> w = c.getValue();
        String where = w.getSqlSegment();
        assertThat(where)
                .as("CAS 谓词: 本期未提过").contains("dispute_at IS NULL")
                .as("归属校验不能因为条件更新而丢掉").contains("tenant_id").contains("counterparty_id")
                .as("软删过滤").contains("deleted = 0");
    }

    /**
     * 并发: 两个请求都通过了"本期未提过"的检查, 但只有先落库的那个能改到行 ——
     * 后到的那个影响 0 行, 它的理由不能覆盖先到的那条, 必须收敛成 409。
     */
    @Test
    @DisplayName("条件更新影响 0 行 (并发被别人抢先) → 409_005, 不返回成功")
    void raiseDispute_concurrentUpdateAffectsZeroRows() {
        when(reconMapper.selectOne(any())).thenReturn(openRecon());
        when(closingMapper.selectOne(any())).thenReturn(closedClosing());
        when(adjustmentService.get(TENANT, 31L)).thenReturn(adjustment("DRAFT"));
        when(adjustmentService.submit(TENANT, 31L, USER)).thenReturn(adjustment("PENDING_L1"));
        when(reconMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, new BigDecimal("-12.00"), REASON)))
                .satisfies(e -> assertFinanceError(e, FinanceErrorCode.RECON_NOT_DISPUTABLE,
                        "concurrently"));
    }

    @Test
    @DisplayName("只质疑不主张金额 → dispute_amount 存 null, 不是 0")
    void raiseDispute_nullClaimStoredAsNull() {
        when(reconMapper.selectOne(any())).thenReturn(openRecon());
        when(closingMapper.selectOne(any())).thenReturn(closedClosing());
        when(adjustmentService.get(TENANT, 31L)).thenReturn(adjustment("DRAFT"));
        when(adjustmentService.submit(TENANT, 31L, USER)).thenReturn(adjustment("PENDING_L1"));
        stubUpdateHitsOneRow();

        service.raiseDispute(TENANT, ME, USER, new DisputeRequest(11L, null, REASON));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<BillingReconciliation>> c =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(reconMapper).update(isNull(), c.capture());
        LambdaUpdateWrapper<BillingReconciliation> w = c.getValue();
        assertThat(w.getSqlSet()).contains("dispute_amount=");
        // 显式写 NULL —— 不能靠"实体 null 字段被跳过"侥幸 (那会把上一条的值留在库里)
        assertThat(w.getParamNameValuePairs().values()).containsNull();
    }

    /**
     * 理由恰好 10 字 → 放行 (长度闸门不能误伤刚好达标的合法请求)。
     */
    @Test
    @DisplayName("理由恰好 10 字 → 放行")
    void raiseDispute_reasonExactlyMinLengthAccepted() {
        when(reconMapper.selectOne(any())).thenReturn(openRecon());
        when(closingMapper.selectOne(any())).thenReturn(closedClosing());
        when(adjustmentService.get(TENANT, 31L)).thenReturn(adjustment("DRAFT"));
        when(adjustmentService.submit(TENANT, 31L, USER)).thenReturn(adjustment("PENDING_L1"));
        stubUpdateHitsOneRow();

        DisputeResult out = service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, null, "理".repeat(10)));

        assertThat(out.adjustmentStatus()).isEqualTo("PENDING_L1");
    }

    // ------------------------------------------------------------------ 导出

    /**
     * 模拟 MyBatis-Plus 的 {@code selectPage} 的<b>返回值语义</b>: 返回一个带着
     * {@code records} 的页。
     *
     * <p><b>刻意不就地回填传进去的那个 page</b>。生产环境 MP 3.5.7 的
     * {@code selectPage} 是"就地填充入参 page + 原样返回它", 两种读法都拿得到数据;
     * 但那正是<b>读局部变量</b>这种写法看不出问题的地方 —— 单测里若也把入参回填,
     * "读返回值"与"读局部变量"两种写法在测试里完全等价, 哪天有人改成读局部变量,
     * 导出就会在别的 mapper 桩下悄悄变成 0 行, 而测试还绿着。
     *
     * <p>所以这里让 mock <b>只返回</b>结果、不回填入参: 导出代码必须读
     * {@code selectPage} 的<b>返回值</b>, 否则就拿不到数据行 —— 这条契约被
     * {@code exportInvoices_writesHeaderAndRows} 钉死 (若改成读局部 page,
     * 该用例会红)。
     */
    @SuppressWarnings("unchecked")
    private static <T> Answer<Page<T>> fillPage(List<T> records) {
        return inv -> {
            Page<T> p = new Page<>(1, records.isEmpty() ? 1 : records.size());
            p.setRecords(records);
            p.setTotal(records.size());
            return p;
        };
    }

    private static BillingInvoice sampleInvoice() {
        BillingInvoice i = new BillingInvoice();
        i.setId(5L);
        i.setInvoiceNo("INV-001");
        i.setTenantId(TENANT);
        i.setCounterpartyId(ME);
        i.setDirection("RECEIVABLE");
        i.setBusinessType("FREIGHT");
        i.setPeriodYear(2026);
        i.setPeriodMonth(9);
        i.setCurrency("CNY");
        i.setGrossAmount(new BigDecimal("106.00"));
        i.setTaxAmount(new BigDecimal("6.00"));
        i.setNetAmount(new BigDecimal("100.00"));
        i.setFxRateAtCreation(new BigDecimal("7.1000"));
        i.setStatus("ISSUED");
        return i;
    }

    @Test
    @DisplayName("导出发票 → xlsx: 表头 10 列 + 数据行, 且金额写的是原值")
    void exportInvoices_writesHeaderAndRows() throws Exception {
        when(invoiceMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(sampleInvoice())));

        byte[] bytes = service.exportInvoices(TENANT, ME, "2026-09", null);

        assertThat(bytes).isNotEmpty();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("发票");
            assertThat(sheet).isNotNull();
            Row head = sheet.getRow(0);
            assertThat(head.getCell(0).getStringCellValue()).isEqualTo("发票号");
            assertThat(head.getLastCellNum()).isEqualTo((short) 10);   // 10 列表头
            Row row = sheet.getRow(1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("INV-001");
            assertThat(row.getCell(1).getStringCellValue()).isEqualTo("2026-09");
            assertThat(row.getCell(4).getStringCellValue()).isEqualTo("106.00");
            assertThat(row.getCell(8).getStringCellValue()).isEqualTo("ISSUED");
        }
    }

    @Test
    @DisplayName("导出发票的查询必须带 counterparty —— 不能导出别家商家的发票")
    void exportInvoices_scopesToOwnMerchantOnly() {
        when(invoiceMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of()));

        service.exportInvoices(TENANT, ME, null, null);

        ArgumentCaptor<LambdaQueryWrapper<BillingInvoice>> c = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(invoiceMapper).selectPage(any(), c.capture());
        // 必须先渲染一次 SQL 片段 —— MP 的参数值 (getParamNameValuePairs) 是在
        // getSqlSegment() 真正拼接那些 {ew.paramName} 片段时才塞进去的,
        // 没渲染就是空 map, 断言会看到 "[]" 而什么也证明不了。
        c.getValue().getSqlSegment();
        assertThat(c.getValue().getParamNameValuePairs().values()).contains(TENANT, ME);
        // 再钉一层: 谓词里确实有这两列 (参数值对了但列名错了同样会导出别家数据)
        assertThat(c.getValue().getSqlSegment())
                .contains("tenant_id").contains("counterparty_id");
    }

    @Test
    @DisplayName("导出对账单 → xlsx: 含异议三列 + 闭期标记")
    void exportReconciliations_writesDisputeColumns() throws Exception {
        BillingReconciliation r = openRecon();
        r.setDisputeAmount(new BigDecimal("-12.00"));
        r.setDisputeReason("9 月 3 日那票没送到");
        r.setDisputeAt(LocalDateTime.of(2026, 10, 1, 8, 0));
        when(reconMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(r)));
        when(closingMapper.selectList(any())).thenReturn(List.of());

        byte[] bytes = service.exportReconciliations(TENANT, ME, "2026-09");

        assertThat(bytes).isNotEmpty();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("对账单");
            assertThat(sheet).isNotNull();
            Row head = sheet.getRow(0);
            assertThat(head.getLastCellNum()).isEqualTo((short) 11);   // 11 列表头
            Row row = sheet.getRow(1);
            assertThat(row.getCell(6).getStringCellValue()).isEqualTo("开放");  // 未闭期
            assertThat(row.getCell(8).getStringCellValue()).isEqualTo("-12.00");
            assertThat(row.getCell(9).getStringCellValue()).isEqualTo("9 月 3 日那票没送到");
        }
    }

    @Test
    @DisplayName("导出时 period 格式非法 → BAD_REQUEST (不是 500)")
    void exportInvoices_invalidPeriodThrowsBadRequest() {
        assertThatThrownBy(() -> service.exportInvoices(TENANT, ME, "2026-13", null))
                .isInstanceOf(FinanceException.class)
                .extracting(e -> ((FinanceException) e).getErrorCode())
                .isEqualTo(FinanceErrorCode.BAD_REQUEST);

        assertThatThrownBy(() -> service.exportReconciliations(TENANT, ME, "abc"))
                .isInstanceOf(FinanceException.class)
                .extracting(e -> ((FinanceException) e).getErrorCode())
                .isEqualTo(FinanceErrorCode.BAD_REQUEST);
    }
}
