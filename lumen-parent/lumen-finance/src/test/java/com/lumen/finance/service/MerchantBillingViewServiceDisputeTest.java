package com.lumen.finance.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    @DisplayName("对账单不存在 → 404_006")
    void raiseDispute_unknownRecon() {
        when(reconMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, null, "货没到")))
                .isInstanceOf(FinanceException.class)
                .extracting(e -> ((FinanceException) e).getErrorCode())
                .isEqualTo(FinanceErrorCode.RECONCILIATION_NOT_FOUND);
    }

    @Test
    @DisplayName("已提过异议 → 409_005, 且不碰调账单")
    void raiseDispute_alreadyDisputed() {
        BillingReconciliation r = openRecon();
        r.setDisputeAt(LocalDateTime.of(2026, 10, 1, 8, 0));
        when(reconMapper.selectOne(any())).thenReturn(r);

        assertThatThrownBy(() -> service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, null, "再提一次")))
                .isInstanceOf(FinanceException.class)
                .extracting(e -> ((FinanceException) e).getErrorCode())
                .isEqualTo(FinanceErrorCode.RECON_NOT_DISPUTABLE);
        verify(adjustmentService, never()).submit(any(), any(), any());
    }

    @Test
    @DisplayName("期间已 CLOSED → 409_005")
    void raiseDispute_closedPeriod() {
        when(reconMapper.selectOne(any())).thenReturn(openRecon());
        BillingMonthlyClosing c = new BillingMonthlyClosing();
        c.setPeriod("2026-09");
        c.setStatus("CLOSED");
        when(closingMapper.selectOne(any())).thenReturn(c);

        assertThatThrownBy(() -> service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, null, "闭期了也要说")))
                .isInstanceOf(FinanceException.class)
                .extracting(e -> ((FinanceException) e).getErrorCode())
                .isEqualTo(FinanceErrorCode.RECON_NOT_DISPUTABLE);
        verify(adjustmentService, never()).submit(any(), any(), any());
    }

    @Test
    @DisplayName("没有关联调账单 (adjustment_id 为空) → 409_005, 无法推进审批")
    void raiseDispute_noAdjustmentLinked() {
        BillingReconciliation r = openRecon();
        r.setAdjustmentId(null);
        when(reconMapper.selectOne(any())).thenReturn(r);
        when(closingMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, null, "没单可推")))
                .isInstanceOf(FinanceException.class)
                .extracting(e -> ((FinanceException) e).getErrorCode())
                .isEqualTo(FinanceErrorCode.RECON_NOT_DISPUTABLE);
    }

    @Test
    @DisplayName("成功路径: 写三列 + submit 推进 DRAFT→PENDING_L1, 不新建调账单")
    void raiseDispute_writesColumnsAndSubmitsExistingAdjustment() {
        when(reconMapper.selectOne(any())).thenReturn(openRecon());
        when(closingMapper.selectOne(any())).thenReturn(null);
        BillingAdjustment submitted = new BillingAdjustment();
        submitted.setId(31L);
        submitted.setStatus("PENDING_L1");
        when(adjustmentService.submit(TENANT, 31L, USER)).thenReturn(submitted);
        when(reconMapper.updateById(any(BillingReconciliation.class))).thenReturn(1);

        DisputeResult out = service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, new BigDecimal("-12.00"), "9 月 3 日那票没送到"));

        assertThat(out.reconId()).isEqualTo(11L);
        assertThat(out.adjustmentId()).isEqualTo(31L);
        assertThat(out.adjustmentStatus()).isEqualTo("PENDING_L1");
        assertThat(out.disputeAt()).isNotNull();

        ArgumentCaptor<BillingReconciliation> c = ArgumentCaptor.forClass(BillingReconciliation.class);
        verify(reconMapper).updateById(c.capture());
        assertThat(c.getValue().getDisputeReason()).isEqualTo("9 月 3 日那票没送到");
        assertThat(c.getValue().getDisputeAmount()).isEqualByComparingTo("-12.00");
        assertThat(c.getValue().getDisputeAt()).isNotNull();
        // 财务算的账务金额绝不能被商家主张覆盖
        assertThat(c.getValue().getVarianceAmount()).isEqualByComparingTo("-10.0000");
    }

    @Test
    @DisplayName("只质疑不主张金额 → disputeAmount 存 null, 不是 0")
    void raiseDispute_nullClaimStoredAsNull() {
        when(reconMapper.selectOne(any())).thenReturn(openRecon());
        when(closingMapper.selectOne(any())).thenReturn(null);
        BillingAdjustment submitted = new BillingAdjustment();
        submitted.setId(31L);
        submitted.setStatus("PENDING_L1");
        when(adjustmentService.submit(TENANT, 31L, USER)).thenReturn(submitted);
        when(reconMapper.updateById(any(BillingReconciliation.class))).thenReturn(1);

        service.raiseDispute(TENANT, ME, USER, new DisputeRequest(11L, null, "货没到"));

        ArgumentCaptor<BillingReconciliation> c = ArgumentCaptor.forClass(BillingReconciliation.class);
        verify(reconMapper).updateById(c.capture());
        assertThat(c.getValue().getDisputeAmount()).isNull();
    }

    @Test
    @DisplayName("submit 抛 INVALID_STATE_TRANSITION → 409_005, 不吞异常")
    void raiseDispute_nonDraftAdjustmentSurfacesAsNotDisputable() {
        when(reconMapper.selectOne(any())).thenReturn(openRecon());
        when(closingMapper.selectOne(any())).thenReturn(null);
        when(adjustmentService.submit(eq(TENANT), eq(31L), eq(USER)))
                .thenThrow(new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                        "Adjustment status must be DRAFT to submit, got PENDING_L2"));

        assertThatThrownBy(() -> service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, null, "货没到")))
                .isInstanceOf(FinanceException.class)
                .extracting(e -> ((FinanceException) e).getErrorCode())
                .isEqualTo(FinanceErrorCode.RECON_NOT_DISPUTABLE);
        verify(reconMapper, never()).updateById(any(BillingReconciliation.class));
    }

    /**
     * {@code adjustment_id} 悬空 (指向一张已被删或根本不存在的调账单) 时,
     * {@code AdjustmentService.submit} 抛的是 {@code ADJUSTMENT_NOT_FOUND} ——
     * 但从商家视角看, "关联调账单不存在" 就是"没有可推进的关联调账单",
     * 与 {@code adjustment_id} 为空同一性质, 必须统一映射成 {@code RECON_NOT_DISPUTABLE},
     * 否则会漏出 400_015 这个内部码, 与本接口声明的契约不符。
     */
    @Test
    @DisplayName("submit 抛 ADJUSTMENT_NOT_FOUND (悬空 adjustment_id) → 409_005")
    void raiseDispute_danglingAdjustmentSurfacesAsNotDisputable() {
        when(reconMapper.selectOne(any())).thenReturn(openRecon());
        when(closingMapper.selectOne(any())).thenReturn(null);
        when(adjustmentService.submit(eq(TENANT), eq(31L), eq(USER)))
                .thenThrow(new FinanceException(FinanceErrorCode.ADJUSTMENT_NOT_FOUND,
                        "Adjustment 31 not found"));

        assertThatThrownBy(() -> service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, null, "货没到")))
                .isInstanceOf(FinanceException.class)
                .extracting(e -> ((FinanceException) e).getErrorCode())
                .isEqualTo(FinanceErrorCode.RECON_NOT_DISPUTABLE);
        verify(reconMapper, never()).updateById(any(BillingReconciliation.class));
    }

    @Test
    @DisplayName("理由为空 → BAD_REQUEST, 且不碰调账单 (防\"已异议但没理由\"的卡死态)")
    void raiseDispute_blankReasonRejected() {
        when(reconMapper.selectOne(any())).thenReturn(openRecon());
        when(closingMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.raiseDispute(TENANT, ME, USER,
                new DisputeRequest(11L, null, "   ")))
                .isInstanceOf(FinanceException.class)
                .extracting(e -> ((FinanceException) e).getErrorCode())
                .isEqualTo(FinanceErrorCode.BAD_REQUEST);
        verify(adjustmentService, never()).submit(any(), any(), any());
        verify(reconMapper, never()).updateById(any(BillingReconciliation.class));
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
