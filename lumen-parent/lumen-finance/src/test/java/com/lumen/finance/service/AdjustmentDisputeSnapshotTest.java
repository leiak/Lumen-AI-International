package com.lumen.finance.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.extension.state.StateMachineEngine;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.entity.BillingAdjustment;
import com.lumen.finance.entity.BillingReconciliation;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingAdjustmentMapper;
import com.lumen.finance.mapper.BillingInvoiceMapper;
import com.lumen.finance.mapper.BillingReconciliationMapper;
import com.lumen.finance.service.impl.AdjustmentServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 4.5C-2.1 — 调账单的商家异议快照回填 (缺口 #13).
 *
 * <p>覆盖 [N13-2] ~ [N13-6]。
 *
 * <p><b>为什么要自己初始化 MP 的 lambda 缓存</b>: 纯 Mockito 单测没有 Spring 上下文,
 * {@code TableInfoHelper} 的列名缓存是空的, 任何 {@code eq(Entity::getX, v)} 在生成
 * SQL 片段时都会抛 "can not find lambda cache for this entity"。本类会断言 wrapper
 * 生成的 SQL, 所以必须自己补这一步。
 *
 * <p><b>断言参数 map 的坑</b>: MP 的参数 map 是**懒生成**的 —— 必须先调
 * {@code getSqlSegment()} 再读 {@code getParamNameValuePairs()}, 否则拿到的是空 map,
 * 断言变成恒真。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdjustmentService 商家异议快照")
class AdjustmentDisputeSnapshotTest {

    @Mock private BillingAdjustmentMapper adjustmentMapper;
    @Mock private BillingInvoiceMapper invoiceMapper;
    @Mock private BillingReconciliationMapper reconMapper;
    @Mock private StateMachineEngine stateMachine;
    @Mock private ExchangeRateService exchangeRateService;
    @Mock private TaxRateService taxRateService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private AdjustmentServiceImpl service;

    private static final Long TENANT = 1L;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, BillingAdjustment.class);
        TableInfoHelper.initTableInfo(assistant, BillingReconciliation.class);
    }

    @BeforeEach
    void setUp() {
        service = new AdjustmentServiceImpl(adjustmentMapper, invoiceMapper, reconMapper,
                stateMachine, exchangeRateService, taxRateService, eventPublisher);
    }

    private static BillingAdjustment adjustment(long id) {
        BillingAdjustment a = new BillingAdjustment();
        a.setId(id);
        a.setTenantId(TENANT);
        a.setStatus("PENDING_L1");
        return a;
    }

    private static BillingReconciliation recon(long adjustmentId, String reason,
                                               String amount, LocalDateTime at) {
        BillingReconciliation r = new BillingReconciliation();
        r.setId(900L);
        r.setTenantId(TENANT);
        r.setAdjustmentId(adjustmentId);
        r.setDisputeReason(reason);
        r.setDisputeAmount(amount == null ? null : new BigDecimal(amount));
        r.setDisputeAt(at);
        return r;
    }

    @Test
    @DisplayName("[N13-2] list: 回填 dispute_reason / dispute_amount / dispute_at")
    void list_attachesDisputeSnapshot() {
        when(adjustmentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    Page<BillingAdjustment> p = inv.getArgument(0);
                    p.setRecords(List.of(adjustment(31L)));
                    p.setTotal(1);
                    return p;
                });
        when(reconMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(recon(31L, "9 月 3 日那票没送到", "-12.0000",
                        LocalDateTime.of(2026, 9, 1, 10, 0))));

        List<BillingAdjustment> rows = service.list(TENANT, null, null, 1, 20);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getDisputeReason()).isEqualTo("9 月 3 日那票没送到");
        assertThat(rows.get(0).getDisputeAmount()).isEqualByComparingTo(new BigDecimal("-12.0000"));
        assertThat(rows.get(0).getDisputeAt()).isEqualTo(LocalDateTime.of(2026, 9, 1, 10, 0));
    }

    @Test
    @DisplayName("[N13-3] 同一 adjustment 被多条 recon 认领 → 取 dispute_at 最新的那条")
    void list_takesLatestDisputeWhenClaimedTwice() {
        when(adjustmentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    Page<BillingAdjustment> p = inv.getArgument(0);
                    p.setRecords(List.of(adjustment(31L)));
                    p.setTotal(1);
                    return p;
                });
        // 服务端按 dispute_at DESC 排序返回 —— 实现只取第一条。
        when(reconMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(
                        recon(31L, "较新的理由", "-1.0000", LocalDateTime.of(2026, 9, 2, 10, 0)),
                        recon(31L, "较老的理由", "-2.0000", LocalDateTime.of(2026, 9, 1, 10, 0))));

        List<BillingAdjustment> rows = service.list(TENANT, null, null, 1, 20);

        assertThat(rows.get(0).getDisputeReason()).isEqualTo("较新的理由");

        // 锁住排序本身: 光靠 mock 的返回顺序不够 —— 实现里必须真的有 ORDER BY dispute_at DESC,
        // 否则删掉排序后这个用例仍然绿 (mock 不执行 SQL)。
        ArgumentCaptor<LambdaQueryWrapper<BillingReconciliation>> cap =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(reconMapper).selectList(cap.capture());
        assertThat(cap.getValue().getSqlSegment()).contains("ORDER BY dispute_at DESC");
    }

    @Test
    @DisplayName("[N13-4] 没有异议的调账单 → 3 个字段保持 null")
    void list_leavesSnapshotNullWhenNoDispute() {
        when(adjustmentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    Page<BillingAdjustment> p = inv.getArgument(0);
                    p.setRecords(List.of(adjustment(31L)));
                    p.setTotal(1);
                    return p;
                });
        when(reconMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        List<BillingAdjustment> rows = service.list(TENANT, null, null, 1, 20);

        assertThat(rows.get(0).getDisputeReason()).isNull();
        assertThat(rows.get(0).getDisputeAmount()).isNull();
        assertThat(rows.get(0).getDisputeAt()).isNull();
    }

    @Test
    @DisplayName("[N13-6] disputed=true 但一条异议都没有 → 返回空列表, 不发 IN ()")
    void list_disputedTrueWithNoDispute_returnsEmpty() {
        when(reconMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        List<BillingAdjustment> rows = service.list(TENANT, null, Boolean.TRUE, 1, 20);

        assertThat(rows).isEmpty();
        verify(adjustmentMapper, never()).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("[N13-5] disputed=false 且一条异议都没有 → 不过滤, 返回全部")
    void list_disputedFalseWithNoDispute_doesNotFilter() {
        when(reconMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(adjustmentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    Page<BillingAdjustment> p = inv.getArgument(0);
                    p.setRecords(List.of(adjustment(31L)));
                    p.setTotal(1);
                    return p;
                });

        List<BillingAdjustment> rows = service.list(TENANT, null, Boolean.FALSE, 1, 20);

        assertThat(rows).hasSize(1);

        // 锁住"不加任何过滤条件": 光看返回行数不够 —— mock 无论 wrapper 是什么都返回同一条记录,
        // 实现即使多加了 notIn 过滤, 上面的 hasSize(1) 仍然绿。
        // 注意 "id IN" 不是 "id NOT IN" 的子串 (中间隔着 "NOT "), 两个断言互不干扰。
        // 用 " AND id IN" 而不是裸 "id IN": 将来若新增 invoice_id / source_invoice_id 之类
        // 的过滤, 裸 "id IN" 会把 "..._id IN (...)" 也算进去 —— 假红, 与本用例意图无关。
        ArgumentCaptor<LambdaQueryWrapper<BillingAdjustment>> cap =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(adjustmentMapper).selectPage(any(Page.class), cap.capture());
        String sql = cap.getValue().getSqlSegment();
        assertThat(sql).doesNotContain(" AND id IN");
        assertThat(sql).doesNotContain("id NOT IN");
    }

    @Test
    @DisplayName("[N13-5] disputed=false 且存在异议 → 查询条件含 id NOT IN (排除有异议的)")
    void list_disputedFalse_filtersOutDisputedIds() {
        // 有一张调账单 (31) 被异议过 → id 集 = {31}; disputed=false 的语义就是"排除它"
        when(reconMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(recon(31L, "理由", "-1.0000", LocalDateTime.now())));
        when(adjustmentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    Page<BillingAdjustment> p = inv.getArgument(0);
                    p.setRecords(List.of(adjustment(32L)));
                    p.setTotal(1);
                    return p;
                });

        service.list(TENANT, null, Boolean.FALSE, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingAdjustment>> cap =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(adjustmentMapper).selectPage(any(Page.class), cap.capture());
        LambdaQueryWrapper<BillingAdjustment> w = cap.getValue();
        // 真正干活的分支是 notIn —— 只断言"不出现 id IN"锁不住它 (notIn 里也含 "IN")。
        // "id IN" 不是 "id NOT IN" 的子串 (中间隔着 "NOT "), 两个断言互不干扰。
        // 反向断言用 " AND id IN": 裸 "id IN" 会把将来的 "..._id IN (...)" 也算进去。
        assertThat(w.getSqlSegment()).contains("id NOT IN").doesNotContain(" AND id IN");
        // 参数 map 是懒生成的 —— 上面的 getSqlSegment() 已经把它填好了。
        assertThat(w.getParamNameValuePairs().values()).contains(31L);
    }

    /**
     * attach 那次 recon 查询是**有界**的 —— IN 列表恰好是当页的 adjustment id。
     *
     * <p>这是缓解「{@code disputedAdjustmentIds} 无上界」问题的关键性质: 快照回填只查
     * 当页 (≤200 个 id), 不会把无上界的 id 集拉进 SQL。
     *
     * <p>没有这条断言的话, 将来有人把 {@code attachDisputes} 改成「不带 in 过滤、查全量
     * 再回填」, 其它用例**全绿**, 而这个缓解就悄悄丢了。
     */
    @Test
    @DisplayName("attach 那次 recon 查询是有界的: IN 列表恰好是当页的 adjustment id")
    void list_attachReconQueryIsBoundedToPage() {
        when(adjustmentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    Page<BillingAdjustment> p = inv.getArgument(0);
                    p.setRecords(List.of(adjustment(31L), adjustment(32L)));
                    p.setTotal(2);
                    return p;
                });
        when(reconMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        service.list(TENANT, null, null, 1, 20);

        // disputed == null → 只查一次 recon (attach 那次); id 集那次根本不会发出。
        ArgumentCaptor<LambdaQueryWrapper<BillingReconciliation>> rcap =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(reconMapper).selectList(rcap.capture());
        LambdaQueryWrapper<BillingReconciliation> attach = rcap.getValue();
        assertThat(attach.getSqlSegment()).contains("adjustment_id IN");
        // 参数 map 懒生成 —— 上面的 getSqlSegment() 已经把它填好了:
        // tenantId + 当页 2 个 adjustment id, 恰好 3 个 —— 多了就说明查了不止当页。
        assertThat(attach.getParamNameValuePairs().values())
                .contains(TENANT, 31L, 32L)
                .hasSize(3);
    }

    @Test
    @DisplayName("[N13-5] disputed=true → 查询条件含 adjustment id 的 IN")
    void list_disputedTrue_filtersByDisputedIds() {
        when(reconMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(recon(31L, "理由", "-1.0000", LocalDateTime.now())));
        when(adjustmentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    Page<BillingAdjustment> p = inv.getArgument(0);
                    p.setRecords(List.of(adjustment(31L)));
                    p.setTotal(1);
                    return p;
                });

        service.list(TENANT, null, Boolean.TRUE, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingAdjustment>> cap =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(adjustmentMapper).selectPage(any(Page.class), cap.capture());
        LambdaQueryWrapper<BillingAdjustment> w = cap.getValue();
        // 参数 map 是懒生成的 —— 必须先调 getSqlSegment() 再读参数 map。
        assertThat(w.getSqlSegment()).contains("id IN");
        assertThat(w.getParamNameValuePairs().values()).contains(31L);

        // 锁住 disputedAdjustmentIds 那条 recon 查询的 V39 谓词 —— 判据是 dispute_at 非空
        // (它是幂等标记), 不是 dispute_reason (商家可以「仅质疑不主张」)。mock 不执行
        // SQL 谓词, 所以只能靠 SQL 片段断言; 本用例里 recon 被查了两次 (取 id 集 + 回填),
        // 两条都得带这个谓词。
        ArgumentCaptor<LambdaQueryWrapper<BillingReconciliation>> rcap =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(reconMapper, times(2)).selectList(rcap.capture());
        assertThat(rcap.getAllValues()).allSatisfy(q -> assertThat(q.getSqlSegment())
                .contains("dispute_at IS NOT NULL")
                .contains("deleted = 0"));
    }

    @Test
    @DisplayName("[N13-2] get: 同样回填快照")
    void get_attachesDisputeSnapshot() {
        when(adjustmentMapper.selectById(31L)).thenReturn(adjustment(31L));
        when(reconMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(recon(31L, "理由", "-3.0000", LocalDateTime.now())));

        BillingAdjustment a = service.get(TENANT, 31L);

        assertThat(a.getDisputeReason()).isEqualTo("理由");
        assertThat(a.getDisputeAmount()).isEqualByComparingTo(new BigDecimal("-3.0000"));
    }

    @Test
    @DisplayName("[N13-2] recon 查询限定 tenant_id / deleted = 0 / dispute_at IS NOT NULL")
    void get_reconQueryIsTenantAndDeletedScoped() {
        when(adjustmentMapper.selectById(31L)).thenReturn(adjustment(31L));
        when(reconMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        service.get(TENANT, 31L);

        ArgumentCaptor<LambdaQueryWrapper<BillingReconciliation>> cap =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(reconMapper).selectList(cap.capture());
        LambdaQueryWrapper<BillingReconciliation> w = cap.getValue();
        assertThat(w.getSqlSegment())
                .contains("tenant_id")
                .contains("deleted = 0")
                // V39 语义: dispute_at 非空才是"已被异议过"(兼作幂等标记),
                // dispute_amount 可以为 NULL (商家「仅质疑不主张」) —— 所以判据是 dispute_at,
                // 不是 dispute_reason。mock 不执行 SQL 谓词, 这个断言就是唯一的锁。
                .contains("dispute_at IS NOT NULL");
        assertThat(w.getParamNameValuePairs().values()).contains(TENANT, 31L);
    }

    @Test
    @DisplayName("get: 调账单不存在 → ADJUSTMENT_NOT_FOUND (既有行为不变)")
    void get_missingStillThrows() {
        when(adjustmentMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.get(TENANT, 999L))
                .isInstanceOf(FinanceException.class)
                .extracting("errorCode")
                .isEqualTo(FinanceErrorCode.ADJUSTMENT_NOT_FOUND);
    }

    /**
     * 空结果也返回**可变** List —— 恢复改动前的契约。
     *
     * <p>改动前是 {@code records == null ? List.of() : new ArrayList<>(records)}: null 分支
     * 返回不可变的 {@code List.of()}, 非空分支返回可变的 {@code ArrayList} —— 两支可变性
     * 本来就不一致。曾经有一版把空结果也写成 {@code List.of()}, 于是调用方
     * {@code list(...).add(...)} 会在空结果上抛 {@code UnsupportedOperationException}
     * 而在非空结果上不抛。
     *
     * <p>注: MP 的 {@code Page.getRecords()} 实际不会返回 null, 所以现实中原本就是可变的 ——
     * 现在的写法只是把语义讲清楚并锁住它。
     */
    @Test
    @DisplayName("list: 空结果也返回可变 List (两支可变性一致)")
    void list_emptyResultIsMutable() {
        when(adjustmentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(new Page<BillingAdjustment>());

        List<BillingAdjustment> rows = service.list(TENANT, null, null, 1, 20);

        assertThat(rows).isEmpty();
        assertThatCode(() -> rows.add(adjustment(1L))).doesNotThrowAnyException();
    }

    // ---------- 取数与回填拆开: 写路径不查 recon ----------

    /**
     * 公开 {@code get()} **必须**查 recon —— admin 详情端点要的就是这份快照。
     *
     * <p>与下面几个"写路径不查 recon"的用例配对, 把"读路径查 / 写路径不查"这条边界钉死。
     */
    @Test
    @DisplayName("get (公开): 必须查 recon —— 详情端点要快照")
    void get_doesQueryReconciliation() {
        when(adjustmentMapper.selectById(31L)).thenReturn(adjustment(31L));
        when(reconMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        service.get(TENANT, 31L);

        verify(reconMapper).selectList(any(LambdaQueryWrapper.class));
    }

    /**
     * 写路径 (submit / l1Approve / ...) 走私有的 {@code load()} —— **不查** recon。
     *
     * <p>这是本次改动宣称的主要收益 ("取数与回填拆开"), 也最容易被后来人顺手改回去
     * (公开的 {@code get()} 名字更自然)。所以必须用 {@code never()} 把它钉死 ——
     * 只断言返回值的话, 把 {@code load()} 改回 {@code get()} 用例照样全绿。
     */
    @Test
    @DisplayName("submit: 写路径不查 recon (不白付一次查询), 且状态真的跃迁到 PENDING_L1")
    void submit_doesNotQueryReconciliation() {
        BillingAdjustment adj = adjustment(31L);
        adj.setStatus(FinanceConstants.ADJ_STATUS_DRAFT);
        when(adjustmentMapper.selectById(31L)).thenReturn(adj);

        BillingAdjustment result = service.submit(TENANT, 31L, 900L);

        verify(reconMapper, never()).selectList(any(LambdaQueryWrapper.class));
        // 光"没查 recon"不够 —— 还得证明整条状态跃迁真的走完了
        // (否则"取到 adjustment 就直接 return"的退化实现照样全绿)。
        assertThat(result.getStatus()).isEqualTo(FinanceConstants.ADJ_STATUS_PENDING_L1);
        verify(adjustmentMapper).updateById(any(BillingAdjustment.class));
    }

    /** 同上, 审批路径 (PENDING_L1 → PENDING_L2) 也不查 recon, 且跃迁真的发生。 */
    @Test
    @DisplayName("l1Approve: 写路径不查 recon, 且状态真的跃迁到 PENDING_L2")
    void l1Approve_doesNotQueryReconciliation() {
        BillingAdjustment adj = adjustment(31L);
        adj.setStatus(FinanceConstants.ADJ_STATUS_PENDING_L1);
        when(adjustmentMapper.selectById(31L)).thenReturn(adj);

        BillingAdjustment result = service.l1Approve(TENANT, 31L, 900L, "L1 ok");

        verify(reconMapper, never()).selectList(any(LambdaQueryWrapper.class));
        assertThat(result.getStatus()).isEqualTo(FinanceConstants.ADJ_STATUS_PENDING_L2);
        verify(adjustmentMapper).updateById(any(BillingAdjustment.class));
    }

    /** 同上, 过账路径 (L2_APPROVED → POSTED, 还要发 ClosingAffectedEvent) 也不查 recon。 */
    @Test
    @DisplayName("markPosted: 写路径不查 recon, 且状态真的跃迁到 POSTED")
    void markPosted_doesNotQueryReconciliation() {
        BillingAdjustment adj = adjustment(31L);
        adj.setStatus(FinanceConstants.ADJ_STATUS_L2_APPROVED);
        when(adjustmentMapper.selectById(31L)).thenReturn(adj);

        BillingAdjustment result = service.markPosted(TENANT, 31L, 900L);

        verify(reconMapper, never()).selectList(any(LambdaQueryWrapper.class));
        assertThat(result.getStatus()).isEqualTo(FinanceConstants.ADJ_STATUS_POSTED);
        verify(adjustmentMapper).updateById(any(BillingAdjustment.class));
    }
}
