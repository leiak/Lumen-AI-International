package com.lumen.finance.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.finance.dto.portal.MerchantDtos;
import com.lumen.finance.dto.portal.MerchantPage;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.stubbing.Answer;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MerchantBillingViewService — 商家可见性契约")
class MerchantBillingViewServiceTest {

    @Mock BillingInvoiceMapper invoiceMapper;
    @Mock BillingReconciliationMapper reconMapper;
    @Mock BillingPaymentRecordMapper paymentMapper;
    @Mock BillingAdjustmentMapper adjustmentMapper;
    @Mock BillingMonthlyClosingMapper closingMapper;

    @InjectMocks MerchantBillingViewServiceImpl service;

    private static final Long TENANT = 1L;
    private static final Long ME = 42L;
    /** 别的商家 —— B3 跨商家泄漏用例里的"对方"。 */
    private static final Long OTHER = 99L;

    /**
     * MyBatis-Plus 的 lambda 列名缓存由 Spring 启动时的 {@code TableInfoHelper} 填充;
     * 纯 Mockito 单测没有 Spring 上下文, 缓存是空的, 于是任何
     * {@code eq(Entity::getX, v)} 在生成 SQL 片段时都会抛
     * {@code MybatisPlusException: can not find lambda cache for this entity}。
     *
     * <p>这里手动补上这一步 —— 这是断言"谓词里到底有什么列"的硬前提: 没有它,
     * 连 {@code getSqlSegment()} 都跑不到, 测试会在断言之前就炸, 变成一个
     * 什么都不证明的红。
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : List.of(BillingInvoice.class, BillingReconciliation.class,
                BillingPaymentRecord.class, BillingAdjustment.class, BillingMonthlyClosing.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    private static String sqlOf(LambdaQueryWrapper<?> wrapper) {
        return wrapper.getSqlSegment();
    }

    /**
     * 跳 1 的 select 列 —— 只取 adjustment_id。
     *
     * <p>认领表的 select 列 —— adjustment_id + counterparty_id。
     *
     * <p>MP 的 {@code getSqlSelect()} 用<b>逗号无空格</b>连接多个 lambda 列, 所以
     * 这里是精确值而不是片段。
     */
    private static final String HOP1_SELECT = "adjustment_id";
    private static final String CLAIM_TABLE_SELECT = "adjustment_id,counterparty_id";

    /**
     * 区分 reconMapper 的两次 selectList: 跳 1 只 select adjustment_id,
     * 认领表 select adjustment_id + counterparty_id。
     *
     * <p>必须能区分 —— 否则"认领表"这一路根本没被单独测到, 跨商家泄漏就会溜过去
     * (这正是 B3 能逃过旧测试的原因)。
     *
     * <p><b>用 equals 而不是 contains</b>: 早先用 {@code contains("counterparty_id")}
     * 判别, 一旦有人给跳 1 的 select 也加上 {@code counterparty_id} (本身无害的改动),
     * 两条查询会被<b>静默互换</b> —— 桩喂错数据, 测试还在绿。精确比较让误路由变成
     * 显式失败 (找不到认领表查询 → orElseThrow)。
     */
    private static boolean isClaimTableQuery(LambdaQueryWrapper<?> w) {
        return CLAIM_TABLE_SELECT.equals(w.getSqlSelect());
    }

    /** 跳 1 查询的判别 —— 与 {@link #isClaimTableQuery} 互为镜像。 */
    private static boolean isHop1Query(LambdaQueryWrapper<?> w) {
        return HOP1_SELECT.equals(w.getSqlSelect());
    }

    /** 让 reconMapper 的两次 selectList 各返回各的: 跳 1 一份, 认领表一份。 */
    private void stubReconSelectList(List<BillingReconciliation> hop1Rows,
                                     List<BillingReconciliation> claimRows) {
        when(reconMapper.selectList(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<BillingReconciliation> w = inv.getArgument(0);
            return isClaimTableQuery(w) ? claimRows : hop1Rows;
        });
    }

    private static BillingReconciliation recon(Long id, Long adjustmentId, Long counterpartyId) {
        BillingReconciliation r = new BillingReconciliation();
        r.setId(id);
        r.setAdjustmentId(adjustmentId);
        r.setCounterpartyId(counterpartyId);
        return r;
    }

    private static BillingAdjustment adjustment(Long id, Long sourceInvoiceId) {
        BillingAdjustment a = new BillingAdjustment();
        a.setId(id);
        a.setSourceInvoiceId(sourceInvoiceId);
        return a;
    }

    /**
     * 忠实地模拟 MyBatis-Plus 的 {@code selectPage}: <b>就地填充</b>传进去的 page
     * 并<b>原样返回它</b> (反编译 mybatis-plus-core-3.5.7 确认的行为)。
     *
     * <p>直接 {@code thenReturn(new Page<>())} 会让 service 读到与请求无关的默认值
     * (新 Page 的 size=10 / total=0), 于是"size 被钳位到多少"这类断言根本测不到
     * —— 测的是一个跟实现无关的常量。
     */
    @SuppressWarnings("unchecked")
    private static <T> Answer<Page<T>> fillPage(List<T> records, long total) {
        return inv -> {
            Page<T> p = (Page<T>) inv.getArgument(0);
            p.setRecords(records);
            p.setTotal(total);
            return p;
        };
    }

    // ---------------------------------------------------------------- 可见性铁律

    @Test
    @DisplayName("listReconciliations 的谓词必须含 tenant + counterparty + direction + deleted 四项")
    void listReconciliations_scopesToMerchantAndFiltersSoftDeleted() {
        when(reconMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));

        service.listReconciliations(TENANT, ME, "2026-09", 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingReconciliation>> c = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(reconMapper).selectPage(any(), c.capture());
        String sql = sqlOf(c.getValue());
        // 三项来自需求 (只给我看的应收), deleted 来自坑 2 (实体无该字段, MP 不会自动补)
        assertThat(sql).contains("tenant_id", "counterparty_id", "direction", "deleted");
        // 参数值也要对, 光有列名不说明过滤的是哪个商家
        assertThat(c.getValue().getParamNameValuePairs().values())
                .contains(TENANT, ME, "RECEIVABLE", "2026-09");
    }

    @Test
    @DisplayName("listPayments 同样四件套齐全 —— 付款表实体也没有 deleted 字段")
    void listPayments_scopesToMerchantAndFiltersSoftDeleted() {
        when(paymentMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));

        service.listPayments(TENANT, ME, "2026-09", 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingPaymentRecord>> c = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(paymentMapper).selectPage(any(), c.capture());
        assertThat(sqlOf(c.getValue())).contains("tenant_id", "counterparty_id", "direction", "deleted");
        assertThat(c.getValue().getParamNameValuePairs().values())
                .contains(TENANT, ME, "RECEIVABLE", "2026-09");
    }

    @Test
    @DisplayName("listInvoices 不手写 deleted —— 发票实体有 @TableLogic, 交给 MP")
    void listInvoices_leavesLogicDeleteToMybatisPlus() {
        when(invoiceMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));

        service.listInvoices(TENANT, ME, "2026-09", "PUSHED", 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingInvoice>> c = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(invoiceMapper).selectPage(any(), c.capture());
        String sql = sqlOf(c.getValue());
        assertThat(sql).contains("tenant_id", "counterparty_id", "direction").doesNotContain("deleted");
        // 发票的期间是 period_year / period_month 两列, 所以参数里是 2026 和 9 而不是
        // "2026-09" —— 拿 CHAR(7) 的期待去断言发票表会永远失败。
        assertThat(c.getValue().getParamNameValuePairs().values())
                .contains(TENANT, ME, "RECEIVABLE", 2026, 9, "PUSHED");
    }

    @Test
    @DisplayName("getReconciliation 命中别人的行 → 404_006, 与查无此行同一码")
    void getReconciliation_foreignRowThrowsNotFound() {
        // mapper 谓词已含 counterparty_id, 所以别人的行根本不会返回 —— 返回 null 即 404
        when(reconMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.getReconciliation(TENANT, ME, 7L))
                .isInstanceOf(FinanceException.class)
                .extracting(e -> ((FinanceException) e).getErrorCode())
                .isEqualTo(FinanceErrorCode.RECONCILIATION_NOT_FOUND);
    }

    @Test
    @DisplayName("getReconciliation 映射闭期状态与三个异议列")
    void getReconciliation_mapsClosedPeriodAndDisputeColumns() {
        BillingReconciliation r = new BillingReconciliation();
        r.setId(7L);
        r.setPeriod("2026-09");
        r.setCurrency("CNY");
        r.setExpectedAmount(new BigDecimal("100.00"));
        r.setActualAmount(new BigDecimal("90.00"));
        r.setVarianceAmount(new BigDecimal("-10.00"));
        r.setReconciliationStatus("ADJUSTMENT_CREATED");
        r.setDisputeAmount(new BigDecimal("-12.00"));
        r.setDisputeReason("9 月 3 日那票没送到");
        r.setDisputeAt(LocalDateTime.of(2026, 10, 2, 9, 30));
        when(reconMapper.selectOne(any())).thenReturn(r);
        when(closingMapper.selectOne(any())).thenReturn(closed("2026-09"));

        MerchantDtos.ReconView v = service.getReconciliation(TENANT, ME, 7L);

        assertThat(v.period()).isEqualTo("2026-09");
        assertThat(v.varianceAmount()).isEqualByComparingTo("-10.00");
        assertThat(v.disputeAmount()).isEqualByComparingTo("-12.00");
        assertThat(v.disputeReason()).isEqualTo("9 月 3 日那票没送到");
        assertThat(v.closed()).isTrue();
    }

    @Test
    @DisplayName("getInvoice 用 period_year + period_month 拼 yyyy-MM")
    void getInvoice_composesPeriodFromYearAndMonth() {
        BillingInvoice inv = new BillingInvoice();
        inv.setId(5L);
        inv.setInvoiceNo("INV-2026-0005");
        inv.setPeriodYear(2026);
        inv.setPeriodMonth(9);
        inv.setCurrency("CNY");
        inv.setGrossAmount(new BigDecimal("113.00"));
        inv.setNetAmount(new BigDecimal("100.00"));
        inv.setStatus("PUSHED");
        // 内部字段即使有值也不许流到 view。InvoiceView 的组件是编译期定死的,
        // 这几个名字压根不在 record 里 —— 所以这里唯一能证明的方式是编译期。
        inv.setRuleId(88L);
        inv.setL1ApproverId(3L);
        inv.setErrorMsg("boom");
        when(invoiceMapper.selectOne(any())).thenReturn(inv);

        MerchantDtos.InvoiceView v = service.getInvoice(TENANT, ME, 5L);

        assertThat(v.period()).isEqualTo("2026-09");
        assertThat(v.netAmount()).isEqualByComparingTo("100.00");
        assertThat(v.invoiceNo()).isEqualTo("INV-2026-0005");
    }

    // ---------------------------------------------------------------- B1 期间格式

    @Test
    @DisplayName("period 格式非法 → BAD_REQUEST 而不是 500 —— 商家手滑不该看到服务器内部错误")
    void listReconciliations_invalidPeriodThrowsBadRequest() {
        assertThatThrownBy(() -> service.listReconciliations(TENANT, ME, "2026-13", 1, 20))
                .isInstanceOf(FinanceException.class)
                .extracting(e -> ((FinanceException) e).getErrorCode())
                .isEqualTo(FinanceErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("period 格式非法 (发票路径) → BAD_REQUEST —— YearMonth.parse 会抛 DateTimeParseException 落成 500")
    void listInvoices_invalidPeriodThrowsBadRequest() {
        assertThatThrownBy(() -> service.listInvoices(TENANT, ME, "abc", null, 1, 20))
                .isInstanceOf(FinanceException.class)
                .extracting(e -> ((FinanceException) e).getErrorCode())
                .isEqualTo(FinanceErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("period 格式非法 (付款路径) → BAD_REQUEST —— 否则 'abc' 静默返回空集, 商家以为没数据")
    void listPayments_invalidPeriodThrowsBadRequest() {
        assertThatThrownBy(() -> service.listPayments(TENANT, ME, "2026-9", 1, 20))
                .isInstanceOf(FinanceException.class)
                .extracting(e -> ((FinanceException) e).getErrorCode())
                .isEqualTo(FinanceErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("period 合法时不该被校验拦下 (正常期间照常查询)")
    void listPayments_validPeriodPassesValidation() {
        when(paymentMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));

        service.listPayments(TENANT, ME, "2026-09", 1, 20);

        verify(paymentMapper).selectPage(any(), any());
    }

    // ---------------------------------------------------------------- B2 确定性排序

    @Test
    @DisplayName("三个列表查询都必须按唯一列 id 排序 —— 否则 LIMIT/OFFSET 分页会重复或漏行")
    void listQueries_orderByUniqueId() {
        when(reconMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));
        when(invoiceMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));
        when(paymentMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));

        service.listReconciliations(TENANT, ME, null, 1, 20);
        service.listInvoices(TENANT, ME, null, null, 1, 20);
        service.listPayments(TENANT, ME, null, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingReconciliation>> rc = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(reconMapper).selectPage(any(), rc.capture());
        assertThat(sqlOf(rc.getValue())).contains("ORDER BY id DESC");

        ArgumentCaptor<LambdaQueryWrapper<BillingInvoice>> ic = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(invoiceMapper).selectPage(any(), ic.capture());
        assertThat(sqlOf(ic.getValue())).contains("ORDER BY id DESC");

        ArgumentCaptor<LambdaQueryWrapper<BillingPaymentRecord>> pc = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(paymentMapper).selectPage(any(), pc.capture());
        assertThat(sqlOf(pc.getValue())).contains("ORDER BY id DESC");
    }

    @Test
    @DisplayName("调账单排序必须带唯一 tie-breaker —— createdAt 不是唯一列, 只按它排顺序仍未定义")
    void listMyAdjustments_ordersByCreatedAtThenUniqueId() {
        stubReconSelectList(List.of(recon(1L, 31L, ME)), List.of());
        BillingInvoice inv = new BillingInvoice();
        inv.setId(21L);
        when(invoiceMapper.selectList(any())).thenReturn(List.of(inv));
        when(adjustmentMapper.selectList(any())).thenReturn(List.of(adjustment(41L, 21L)));
        when(adjustmentMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));

        service.listMyAdjustments(TENANT, ME, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingAdjustment>> ac = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(adjustmentMapper).selectPage(any(), ac.capture());
        assertThat(sqlOf(ac.getValue())).contains("ORDER BY created_at DESC", "id DESC");
    }

    // ---------------------------------------------------------------- B3 归属两跳

    @Test
    @DisplayName("listMyAdjustments 两跳归属都要按商家收口: recon→adjustmentId, invoice→sourceInvoiceId")
    void listMyAdjustments_goesThroughBothOwnershipHops() {
        stubReconSelectList(List.of(recon(1L, 31L, ME)), List.of(recon(1L, 31L, ME)));
        BillingInvoice inv = new BillingInvoice();
        inv.setId(21L);
        when(invoiceMapper.selectList(any())).thenReturn(List.of(inv));
        BillingAdjustment viaInvoice = new BillingAdjustment();
        viaInvoice.setId(41L);                        // 跳 2 产物, 不是发票 id
        when(adjustmentMapper.selectList(any())).thenReturn(List.of(viaInvoice));
        when(adjustmentMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));

        service.listMyAdjustments(TENANT, ME, 1, 20);

        // 跳 1: recon 按商家过滤后才取 adjustment_id
        ArgumentCaptor<LambdaQueryWrapper<BillingReconciliation>> rc = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(reconMapper, times(2)).selectList(rc.capture());
        LambdaQueryWrapper<BillingReconciliation> hop1 = rc.getAllValues().stream()
                .filter(MerchantBillingViewServiceTest::isHop1Query).findFirst().orElseThrow();
        assertThat(sqlOf(hop1)).contains("tenant_id", "counterparty_id");
        // direction 也要断言 —— 旧测试漏了这条, 少写一个 direction 照样绿
        assertThat(sqlOf(hop1)).contains("direction");
        // deleted 也要断言 —— 软删的 recon 对商家不可见, 不该再把它的调账单认领进来。
        // 变异验证: 删掉跳 1 的 .apply("deleted = 0"), 这条断言必须红 (旧测试全绿)。
        assertThat(sqlOf(hop1)).contains("deleted");

        // 跳 2 的发票侧也要 verify —— 旧测试打了桩却没 verify,
        // 所以"少写一个 counterparty_id"这种漏洞可以溜过去
        ArgumentCaptor<LambdaQueryWrapper<BillingInvoice>> ic = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(invoiceMapper).selectList(ic.capture());
        assertThat(sqlOf(ic.getValue())).contains("tenant_id", "counterparty_id", "direction");

        // 跳 2: 一次 IN 查完, 条件是 source_invoice_id IN (21) + tenant ——
        // 不能只按 invoiceId 查 (那样换个租户就能读到别人的调账单)
        ArgumentCaptor<LambdaQueryWrapper<BillingAdjustment>> sc = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(adjustmentMapper).selectList(sc.capture());
        assertThat(sqlOf(sc.getValue())).contains("tenant_id", "source_invoice_id");
        assertThat(sc.getValue().getParamNameValuePairs().values()).contains(TENANT, 21L);

        // 收口: 最终按 id IN (31, 41) + tenant 取页 —— 31 和 41 都是 adjustment id
        ArgumentCaptor<LambdaQueryWrapper<BillingAdjustment>> ac = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(adjustmentMapper).selectPage(any(), ac.capture());
        assertThat(sqlOf(ac.getValue())).contains("tenant_id");
        assertThat(ac.getValue().getParamNameValuePairs().values()).contains(TENANT, 31L, 41L);
    }

    @Test
    @DisplayName("认领表: 全租户 (不限 counterparty)、不过滤 deleted / direction, 且只覆盖候选 adjustment id")
    void claimTable_isTenantWideAndIgnoresSoftDelete() {
        // 候选集必须非空, 认领表才会被查询 (F1: 认领表已收窄到候选 adjustment id)
        stubReconSelectList(List.of(), List.of());
        BillingInvoice inv = new BillingInvoice();
        inv.setId(21L);
        when(invoiceMapper.selectList(any())).thenReturn(List.of(inv));
        when(adjustmentMapper.selectList(any())).thenReturn(List.of(adjustment(41L, 21L)));
        when(adjustmentMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));

        service.listMyAdjustments(TENANT, ME, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingReconciliation>> rc = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(reconMapper, times(2)).selectList(rc.capture());
        LambdaQueryWrapper<BillingReconciliation> claim = rc.getAllValues().stream()
                .filter(MerchantBillingViewServiceTest::isClaimTableQuery).findFirst().orElseThrow();
        String sql = sqlOf(claim);
        // 必须带 tenant_id (跨租户更不能混)
        assertThat(sql).contains("tenant_id");
        // 不能带 counterparty_id (要的就是全租户的认领关系)
        assertThat(sql).doesNotContain("counterparty_id");
        // 不能带 deleted 过滤 —— 软删只是把 recon 隐藏, 不是解除归属关系
        assertThat(sql).doesNotContain("deleted");
        // 不能带 direction 过滤 —— 认领表只用于**排除**, 方向不对称是刻意的:
        // 写侧对 PAYABLE recon 的调账单, 锚点收窄不到自己家的 RECEIVABLE 发票时会
        // 退回租户级锚点 (可能落在别家发票上), 此时全靠认领表把它从锚点方的跳 2
        // 里剔出去。加上 direction=RECEIVABLE 会让它从跳 2 漏给锚点商家 —— 真跨商家泄漏。
        // 变异验证: 给认领表加 direction = RECEIVABLE, 这条断言必须红 (旧测试全绿)。
        assertThat(sql).doesNotContain("direction");
        // F1: 认领表只覆盖候选 adjustment id, 不是全租户拉取 —— 候选 id 41 必须在参数里。
        // 变异验证: 把 .in(adjustmentId, 候选集) 去掉 (退回全租户拉取), 这条必须红。
        assertThat(sql).contains("IN");
        assertThat(claim.getParamNameValuePairs().values()).contains(TENANT, 41L);
    }

    @Test
    @DisplayName("跨商家泄漏: 调账单被别家 recon 认领、但锚点落在我的发票上 → 必须不出现在我的列表")
    void listMyAdjustments_excludesAdjustmentClaimedByAnotherMerchant() {
        // 跳 1: 我自己的 recon 认领 31
        stubReconSelectList(List.of(recon(1L, 31L, ME)),
                // 认领表: 41 被 counterparty 99 (别家) 认领
                List.of(recon(2L, 41L, OTHER)));
        BillingInvoice inv = new BillingInvoice();
        inv.setId(21L);
        when(invoiceMapper.selectList(any())).thenReturn(List.of(inv));
        // 跳 2 命中: 41 的 source_invoice_id = 21, 正是我的发票
        when(adjustmentMapper.selectList(any())).thenReturn(List.of(adjustment(41L, 21L)));
        when(adjustmentMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));

        service.listMyAdjustments(TENANT, ME, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingAdjustment>> ac = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(adjustmentMapper).selectPage(any(), ac.capture());
        assertThat(sqlOf(ac.getValue())).contains("tenant_id");
        // 31 (我自己的) 在, 41 (别家认领的) 不在
        assertThat(ac.getValue().getParamNameValuePairs().values())
                .contains(TENANT, 31L)
                .doesNotContain(41L);
    }

    @Test
    @DisplayName("同商家认领的调账单照常出现在我的列表里 (认领表过滤不能误伤自己)")
    void listMyAdjustments_keepsAdjustmentClaimedByMe() {
        stubReconSelectList(List.of(recon(1L, 31L, ME)),
                List.of(recon(2L, 41L, ME)));
        BillingInvoice inv = new BillingInvoice();
        inv.setId(21L);
        when(invoiceMapper.selectList(any())).thenReturn(List.of(inv));
        when(adjustmentMapper.selectList(any())).thenReturn(List.of(adjustment(41L, 21L)));
        when(adjustmentMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));

        service.listMyAdjustments(TENANT, ME, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingAdjustment>> ac = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(adjustmentMapper).selectPage(any(), ac.capture());
        // 先物化 SQL 片段 —— MP 的参数 map 是懒生成的, 不先调 getSqlSegment() 就读
        // getParamNameValuePairs() 拿到的是空 map, 断言会变成恒真。
        sqlOf(ac.getValue());
        assertThat(ac.getValue().getParamNameValuePairs().values()).contains(TENANT, 31L, 41L);
    }

    @Test
    @DisplayName("无人认领的调账单 (人工建的) 锚在我的发票上 → 出现在我的列表里")
    void listMyAdjustments_keepsUnclaimedAdjustmentAnchoredOnMyInvoice() {
        // 认领表为空 —— 41 没被任何 recon 认领
        stubReconSelectList(List.of(), List.of());
        BillingInvoice inv = new BillingInvoice();
        inv.setId(21L);
        when(invoiceMapper.selectList(any())).thenReturn(List.of(inv));
        when(adjustmentMapper.selectList(any())).thenReturn(List.of(adjustment(41L, 21L)));
        when(adjustmentMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));

        service.listMyAdjustments(TENANT, ME, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingAdjustment>> ac = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(adjustmentMapper).selectPage(any(), ac.capture());
        sqlOf(ac.getValue());
        assertThat(ac.getValue().getParamNameValuePairs().values()).contains(TENANT, 41L);
    }

    @Test
    @DisplayName("同一张调账单被两个不同商家的 recon 认领 (脏数据) → 归属歧义, 一律排除")
    void listMyAdjustments_excludesAdjustmentClaimedByTwoDifferentMerchants() {
        // 跳 1: 我自己的 recon 认领 31
        stubReconSelectList(List.of(recon(1L, 31L, ME)),
                // 认领表: 41 同时被 42 (我) 和 99 (别家) 认领 —— 归属不可确定
                List.of(recon(2L, 41L, ME), recon(3L, 41L, OTHER)));
        BillingInvoice inv = new BillingInvoice();
        inv.setId(21L);
        when(invoiceMapper.selectList(any())).thenReturn(List.of(inv));
        // 跳 2 命中: 41 的 source_invoice_id = 21, 正是我的发票
        when(adjustmentMapper.selectList(any())).thenReturn(List.of(adjustment(41L, 21L)));
        when(adjustmentMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));

        service.listMyAdjustments(TENANT, ME, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingAdjustment>> ac = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(adjustmentMapper).selectPage(any(), ac.capture());
        sqlOf(ac.getValue());
        // 31 (我自己认领的) 在, 41 (归属歧义) 不在 —— 不能"稳定地挑一个",
        // 顺序无关才是真确定; 宁可漏看不可看错家。
        assertThat(ac.getValue().getParamNameValuePairs().values())
                .contains(TENANT, 31L)
                .doesNotContain(41L);
    }

    @Test
    @DisplayName("认领方 counterparty_id 为 null (脏数据) → 没人能保护锚点方, 排除")
    void listMyAdjustments_excludesAdjustmentClaimedWithoutCounterparty() {
        stubReconSelectList(List.of(recon(1L, 31L, ME)),
                // 认领表: 41 被一条 counterparty_id 为空的 recon 认领
                List.of(recon(2L, 41L, null)));
        BillingInvoice inv = new BillingInvoice();
        inv.setId(21L);
        when(invoiceMapper.selectList(any())).thenReturn(List.of(inv));
        when(adjustmentMapper.selectList(any())).thenReturn(List.of(adjustment(41L, 21L)));
        when(adjustmentMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));

        service.listMyAdjustments(TENANT, ME, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingAdjustment>> ac = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(adjustmentMapper).selectPage(any(), ac.capture());
        sqlOf(ac.getValue());
        assertThat(ac.getValue().getParamNameValuePairs().values())
                .contains(TENANT, 31L)
                .doesNotContain(41L);
    }

    @Test
    @DisplayName("listMyAdjustments 归属集为空 → 返回空页, 不发 in () 查询")
    void listMyAdjustments_emptyOwnershipSkipsQuery() {
        stubReconSelectList(List.of(), List.of());
        when(invoiceMapper.selectList(any())).thenReturn(List.of());

        MerchantPage<MerchantDtos.AdjustmentView> page = service.listMyAdjustments(TENANT, ME, 1, 20);

        assertThat(page.total()).isZero();
        assertThat(page.records()).isEmpty();
        verify(adjustmentMapper, never()).selectPage(any(), any());
        // 跳 2 的 IN 查询也不该发 —— 归属集为空时没有任何可查的 id
        verify(adjustmentMapper, never()).selectList(any());
        // F1: 商家一张发票都没有 → 候选集必为空 → 认领表查询根本不该发
        // (它是全租户范围的查询, 白跑一次的代价不小)
        verify(reconMapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("getMyAdjustment 找不到归属 → ADJUSTMENT_NOT_FOUND (400_015/HTTP 400, 由门户层翻成 404)")
    void getMyAdjustment_unownedThrowsAdjustmentNotFound() {
        stubReconSelectList(List.of(), List.of());
        when(invoiceMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.getMyAdjustment(TENANT, ME, 31L))
                .isInstanceOf(FinanceException.class)
                .extracting(e -> ((FinanceException) e).getErrorCode())
                .isEqualTo(FinanceErrorCode.ADJUSTMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("getMyAdjustment 归属校验拦下别家调账单 → 404, 且从不去查库")
    void getMyAdjustment_ownershipCheckBlocksForeignAdjustmentBeforeQuery() {
        stubReconSelectList(List.of(), List.of());
        when(invoiceMapper.selectList(any())).thenReturn(List.of());
        // 桩返回一张**别家**的调账单 (锚点指向别家的发票 999)。归属校验若真的生效,
        // selectOne 根本不该被调用 —— 所以这条桩必须用 lenient(), 否则 Mockito 的
        // UnnecessaryStubbingException 会让"桩没被用到"直接炸成红, 而不是断言失败。
        BillingAdjustment foreign = new BillingAdjustment();
        foreign.setId(31L);
        foreign.setAdjustmentNo("ADJ-2026-0031");
        foreign.setSourceInvoiceId(999L);          // 别家的发票
        foreign.setReason("对账差异自动生成: 商家 99");
        lenient().when(adjustmentMapper.selectOne(any())).thenReturn(foreign);

        assertThatThrownBy(() -> service.getMyAdjustment(TENANT, ME, 31L))
                .isInstanceOf(FinanceException.class)
                .extracting(e -> ((FinanceException) e).getErrorCode())
                .isEqualTo(FinanceErrorCode.ADJUSTMENT_NOT_FOUND);
        // 只有归属校验真的拦下, 才会得到"抛错 + 从未查库"这两个结果;
        // 把归属校验整段删掉 → selectOne 被调用 → 两条断言同时红。
        verify(adjustmentMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("getMyAdjustment 归属内 → 正常返回 view (正向路径)")
    void getMyAdjustment_ownedReturnsView() {
        stubReconSelectList(List.of(recon(1L, 31L, ME)), List.of(recon(1L, 31L, ME)));
        when(invoiceMapper.selectList(any())).thenReturn(List.of());
        BillingAdjustment a = new BillingAdjustment();
        a.setId(31L);
        a.setAdjustmentNo("ADJ-2026-0031");
        a.setAdjustmentType("DEBIT");
        a.setAmount(new BigDecimal("10.00"));
        a.setCurrency("CNY");
        a.setStatus("L2_APPROVED");
        a.setReason("对账差异自动生成: 期间 2026-09");
        a.setL1Comment("差异已复核");
        a.setL2Comment("同意调账");
        when(adjustmentMapper.selectOne(any())).thenReturn(a);

        MerchantDtos.AdjustmentView v = service.getMyAdjustment(TENANT, ME, 31L);

        assertThat(v.id()).isEqualTo(31L);
        assertThat(v.adjustmentNo()).isEqualTo("ADJ-2026-0031");
        assertThat(v.amount()).isEqualByComparingTo("10.00");
        assertThat(v.l1Comment()).isEqualTo("差异已复核");
        assertThat(v.l2Comment()).isEqualTo("同意调账");
    }

    private static BillingMonthlyClosing closed(String period) {
        BillingMonthlyClosing c = new BillingMonthlyClosing();
        c.setPeriod(period);
        c.setStatus("CLOSED");
        return c;
    }

    // ---------------------------------------------------------------- 列表路径映射

    @Test
    @DisplayName("列表路径也要映射闭期状态 —— 单条路径测过不代表列表路径也映射")
    void listReconciliations_mapsClosedFlagOnListPath() {
        BillingReconciliation r = new BillingReconciliation();
        r.setId(7L);
        r.setPeriod("2026-09");
        when(reconMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(r), 1L));
        when(closingMapper.selectList(any())).thenReturn(List.of(closed("2026-09")));

        MerchantPage<MerchantDtos.ReconView> out = service.listReconciliations(TENANT, ME, "2026-09", 1, 20);

        assertThat(out.records()).hasSize(1);
        assertThat(out.records().get(0).closed()).isTrue();
    }

    @Test
    @DisplayName("列表路径的发票视图要带审批意见 —— 商家看到 REJECTED 却没理由就是黑洞")
    void listInvoices_mapsApprovalCommentsOnListPath() {
        BillingInvoice inv = new BillingInvoice();
        inv.setId(5L);
        inv.setInvoiceNo("INV-2026-0005");
        inv.setPeriodYear(2026);
        inv.setPeriodMonth(9);
        inv.setStatus("REJECTED");
        inv.setL1Comment("金额与运单不符");
        inv.setL2Comment("请财务重新开具");
        when(invoiceMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(inv), 1L));

        MerchantPage<MerchantDtos.InvoiceView> out = service.listInvoices(TENANT, ME, null, null, 1, 20);

        assertThat(out.records()).hasSize(1);
        assertThat(out.records().get(0).l1Comment()).isEqualTo("金额与运单不符");
        assertThat(out.records().get(0).l2Comment()).isEqualTo("请财务重新开具");
    }

    @Test
    @DisplayName("列表路径的调账单视图要带两级审批意见")
    void listMyAdjustments_mapsApprovalCommentsOnListPath() {
        stubReconSelectList(List.of(recon(1L, 31L, ME)), List.of());
        when(invoiceMapper.selectList(any())).thenReturn(List.of());
        BillingAdjustment a = new BillingAdjustment();
        a.setId(31L);
        a.setAdjustmentNo("ADJ-2026-0031");
        a.setL1Comment("差异已复核");
        a.setL2Comment("同意调账");
        when(adjustmentMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(a), 1L));

        MerchantPage<MerchantDtos.AdjustmentView> out = service.listMyAdjustments(TENANT, ME, 1, 20);

        assertThat(out.records()).hasSize(1);
        assertThat(out.records().get(0).l1Comment()).isEqualTo("差异已复核");
        assertThat(out.records().get(0).l2Comment()).isEqualTo("同意调账");
    }

    // ---------------------------------------------------------------- 分页与钳位

    @Test
    @DisplayName("分页 page/size 原样带进 Page 对象, 映射回 records/total/page/size")
    void listPayments_carriesPagingAndMapsView() {
        BillingPaymentRecord p = new BillingPaymentRecord();
        p.setId(9L);
        p.setPeriod("2026-09");
        p.setCurrency("CNY");
        p.setAmount(new BigDecimal("88.00"));
        p.setPaymentDate(LocalDate.of(2026, 9, 28));
        p.setSource("BANK_TRANSFER");
        when(paymentMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(p), 41L));

        MerchantPage<MerchantDtos.PaymentView> out = service.listPayments(TENANT, ME, null, 1, 20);

        assertThat(out.total()).isEqualTo(41);
        assertThat(out.page()).isEqualTo(1);
        assertThat(out.size()).isEqualTo(20);
        assertThat(out.records()).hasSize(1);
        assertThat(out.records().get(0).amount()).isEqualByComparingTo("88.00");
        assertThat(out.records().get(0).paymentDate()).isEqualTo(LocalDate.of(2026, 9, 28));
    }

    @Test
    @DisplayName("size=0 → 生效 20")
    void listPayments_sizeZeroFallsBackTo20() {
        when(paymentMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));

        MerchantPage<MerchantDtos.PaymentView> out = service.listPayments(TENANT, ME, null, 1, 0);

        assertThat(out.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("size=1000 → 截断到 200")
    void listPayments_sizeTooLargeIsCapped() {
        when(paymentMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));

        MerchantPage<MerchantDtos.PaymentView> out = service.listPayments(TENANT, ME, null, 1, 1000);

        assertThat(out.size()).isEqualTo(200);
    }

    @Test
    @DisplayName("空结果与有结果必须回传同一套生效值 —— 否则 size=500 时两边的 size 不一样")
    void emptyPage_reportsSameEffectiveSizeAsNonEmptyPage() {
        when(paymentMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));
        MerchantPage<MerchantDtos.PaymentView> withData = service.listPayments(TENANT, ME, null, 1, 1000);

        stubReconSelectList(List.of(), List.of());
        when(invoiceMapper.selectList(any())).thenReturn(List.of());
        MerchantPage<MerchantDtos.AdjustmentView> empty = service.listMyAdjustments(TENANT, ME, 1, 1000);

        assertThat(withData.size()).isEqualTo(empty.size());
        assertThat(withData.page()).isEqualTo(empty.page());
    }

    @Test
    @DisplayName("period 传 null = 全期可见, 不加 period 条件 (MP 的 eq(col,null) 会生成 col = null)")
    void listPayments_nullPeriodMeansAllPeriods() {
        when(paymentMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));

        service.listPayments(TENANT, ME, null, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingPaymentRecord>> c = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(paymentMapper).selectPage(any(), c.capture());
        assertThat(sqlOf(c.getValue())).doesNotContain("period");
        // doesNotContainNull 而不是 doesNotContain(null) —— 后者绑到 varargs 重载,
        // AssertJ 会收到一个 null 数组并直接抛 NPE, 什么也没断言。
        assertThat(c.getValue().getParamNameValuePairs().values())
                .contains(TENANT, ME, "RECEIVABLE")
                .doesNotContainNull();
    }

    @Test
    @DisplayName("period 传 null 的对账列表同样不加 period 条件 —— mp 的 eq(col,null) 会生成 col = null")
    void listReconciliations_nullPeriodMeansAllPeriods() {
        when(reconMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));

        service.listReconciliations(TENANT, ME, null, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingReconciliation>> c = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(reconMapper).selectPage(any(), c.capture());
        assertThat(sqlOf(c.getValue())).doesNotContain("period");
        assertThat(c.getValue().getParamNameValuePairs().values())
                .contains(TENANT, ME, "RECEIVABLE")
                .doesNotContainNull();
    }

    @Test
    @DisplayName("status 传 null 的发票列表同样不加 status 条件 —— 同一个 mp 陷阱")
    void listInvoices_nullStatusMeansAllStatuses() {
        when(invoiceMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(), 0L));

        service.listInvoices(TENANT, ME, null, null, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingInvoice>> c = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(invoiceMapper).selectPage(any(), c.capture());
        assertThat(sqlOf(c.getValue())).doesNotContain("status");
        assertThat(c.getValue().getParamNameValuePairs().values())
                .contains(TENANT, ME, "RECEIVABLE")
                .doesNotContainNull();
    }

    // ---------------------------------------------------------------- 闭期软删

    @Test
    @DisplayName("闭期批查必须过滤软删月结 —— 一条被软删却仍 CLOSED 的月结会让商家被静默禁止提异议")
    void closedPeriodsFiltersSoftDeletedClosings() {
        BillingReconciliation r = new BillingReconciliation();
        r.setPeriod("2026-09");
        when(reconMapper.selectPage(any(), any())).thenAnswer(fillPage(List.of(r), 1L));
        when(closingMapper.selectList(any())).thenReturn(List.of());

        service.listReconciliations(TENANT, ME, "2026-09", 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingMonthlyClosing>> c =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(closingMapper).selectList(c.capture());
        // 不是恒真断言: 上面三个条件产生的列名只有 tenant_id / period / status,
        // 都不含 "deleted" 子串 —— 只有真写了 .apply("deleted = 0") 才会出现该词。
        assertThat(c.getValue().getSqlSegment()).contains("deleted");
    }

    @Test
    @DisplayName("单条查询的闭期判定同样过滤软删月结")
    void isPeriodClosedFiltersSoftDeletedClosings() {
        BillingReconciliation r = new BillingReconciliation();
        r.setId(7L);
        r.setPeriod("2026-09");
        when(reconMapper.selectOne(any())).thenReturn(r);
        when(closingMapper.selectOne(any())).thenReturn(null);

        service.getReconciliation(TENANT, ME, 7L);

        ArgumentCaptor<LambdaQueryWrapper<BillingMonthlyClosing>> c =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(closingMapper).selectOne(c.capture());
        assertThat(c.getValue().getSqlSegment()).contains("deleted");
    }
}