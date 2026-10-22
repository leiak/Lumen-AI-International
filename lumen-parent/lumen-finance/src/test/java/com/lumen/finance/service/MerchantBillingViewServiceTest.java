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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

    @Test
    @DisplayName("listReconciliations 的谓词必须含 tenant + counterparty + direction + deleted 四项")
    void listReconciliations_scopesToMerchantAndFiltersSoftDeleted() {
        when(reconMapper.selectPage(any(), any())).thenReturn(new Page<>());

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
        when(paymentMapper.selectPage(any(), any())).thenReturn(new Page<>());

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
        when(invoiceMapper.selectPage(any(), any())).thenReturn(new Page<>());

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

    @Test
    @DisplayName("listMyAdjustments 两跳归属都要按商家收口: recon→adjustmentId, invoice→sourceInvoiceId")
    void listMyAdjustments_goesThroughBothOwnershipHops() {
        BillingReconciliation r = new BillingReconciliation();
        r.setAdjustmentId(31L);                       // 跳 1 产物
        when(reconMapper.selectList(any())).thenReturn(List.of(r));
        BillingInvoice inv = new BillingInvoice();
        inv.setId(21L);
        when(invoiceMapper.selectList(any())).thenReturn(List.of(inv));
        BillingAdjustment viaInvoice = new BillingAdjustment();
        viaInvoice.setId(41L);                        // 跳 2 产物, 不是发票 id
        when(adjustmentMapper.selectList(any())).thenReturn(List.of(viaInvoice));
        when(adjustmentMapper.selectPage(any(), any())).thenReturn(new Page<>());

        service.listMyAdjustments(TENANT, ME, 1, 20);

        // 跳 1: recon 按商家过滤后才取 adjustment_id
        ArgumentCaptor<LambdaQueryWrapper<BillingReconciliation>> rc = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(reconMapper).selectList(rc.capture());
        assertThat(sqlOf(rc.getValue())).contains("tenant_id", "counterparty_id");

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
    @DisplayName("listMyAdjustments 归属集为空 → 返回空页, 不发 in () 查询")
    void listMyAdjustments_emptyOwnershipSkipsQuery() {
        when(reconMapper.selectList(any())).thenReturn(List.of());
        when(invoiceMapper.selectList(any())).thenReturn(List.of());

        MerchantPage<MerchantDtos.AdjustmentView> page = service.listMyAdjustments(TENANT, ME, 1, 20);

        assertThat(page.total()).isZero();
        assertThat(page.records()).isEmpty();
        verify(adjustmentMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("getMyAdjustment 找不到归属 → ADJUSTMENT_NOT_FOUND (400_015/HTTP 400, 由门户层翻成 404)")
    void getMyAdjustment_unownedThrowsAdjustmentNotFound() {
        when(reconMapper.selectList(any())).thenReturn(List.of());
        when(invoiceMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.getMyAdjustment(TENANT, ME, 31L))
                .isInstanceOf(FinanceException.class)
                .extracting(e -> ((FinanceException) e).getErrorCode())
                .isEqualTo(FinanceErrorCode.ADJUSTMENT_NOT_FOUND);
    }

    private static BillingMonthlyClosing closed(String period) {
        BillingMonthlyClosing c = new BillingMonthlyClosing();
        c.setPeriod(period);
        c.setStatus("CLOSED");
        return c;
    }

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
        Page<BillingPaymentRecord> mp = Page.of(1, 20, 41);
        mp.setRecords(List.of(p));
        when(paymentMapper.selectPage(any(), any())).thenReturn(mp);

        MerchantPage<MerchantDtos.PaymentView> out = service.listPayments(TENANT, ME, null, 1, 20);

        assertThat(out.total()).isEqualTo(41);
        assertThat(out.page()).isEqualTo(1);
        assertThat(out.size()).isEqualTo(20);
        assertThat(out.records()).hasSize(1);
        assertThat(out.records().get(0).amount()).isEqualByComparingTo("88.00");
        assertThat(out.records().get(0).paymentDate()).isEqualTo(LocalDate.of(2026, 9, 28));
    }

    @Test
    @DisplayName("period 传 null = 全期可见, 不加 period 条件 (MP 的 eq(col,null) 会生成 col = null)")
    void listPayments_nullPeriodMeansAllPeriods() {
        when(paymentMapper.selectPage(any(), any())).thenReturn(new Page<>());

        service.listPayments(TENANT, ME, null, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingPaymentRecord>> c = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(paymentMapper).selectPage(any(), c.capture());
        assertThat(sqlOf(c.getValue())).doesNotContain("period");
        assertThat(c.getValue().getParamNameValuePairs().values())
                .contains(TENANT, ME, "RECEIVABLE")
                .doesNotContainNull();
    }

    @Test
    @DisplayName("period 传 null 的对账列表同样不加 period 条件 —— mp 的 eq(col,null) 会生成 col = null")
    void listReconciliations_nullPeriodMeansAllPeriods() {
        when(reconMapper.selectPage(any(), any())).thenReturn(new Page<>());

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
        when(invoiceMapper.selectPage(any(), any())).thenReturn(new Page<>());

        service.listInvoices(TENANT, ME, null, null, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingInvoice>> c = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(invoiceMapper).selectPage(any(), c.capture());
        assertThat(sqlOf(c.getValue())).doesNotContain("status");
        assertThat(c.getValue().getParamNameValuePairs().values())
                .contains(TENANT, ME, "RECEIVABLE")
                .doesNotContainNull();
    }

    @Test
    @DisplayName("闭期批查必须过滤软删月结 —— 一条被软删却仍 CLOSED 的月结会让商家被静默禁止提异议")
    void closedPeriodsFiltersSoftDeletedClosings() {
        BillingReconciliation r = new BillingReconciliation();
        r.setPeriod("2026-09");
        Page<BillingReconciliation> mp = Page.of(1, 20);
        mp.setRecords(List.of(r));
        when(reconMapper.selectPage(any(), any())).thenReturn(mp);
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