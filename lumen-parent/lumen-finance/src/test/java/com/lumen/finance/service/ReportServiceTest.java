package com.lumen.finance.service;

import com.lumen.finance.dto.ReportDtos.ExportRequest;
import com.lumen.finance.dto.ReportDtos.ReportType;
import com.lumen.finance.entity.*;
import com.lumen.finance.mapper.*;
import com.lumen.finance.service.impl.ReportServiceImpl;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock private BillingMonthlyClosingMapper closingMapper;
    @Mock private BillingInvoiceMapper invoiceMapper;
    @Mock private BillingReconciliationMapper reconMapper;
    @InjectMocks private ReportServiceImpl service;

    @Test
    void exportSummary_returns3RowsWithSnapshot() throws Exception {
        when(closingMapper.selectOne(any())).thenReturn(BillingMonthlyClosing.builder()
            .period("2026-10").snapshotCurrency("CNY")
            .snapshotTotalInvoice(new BigDecimal("10000"))
            .snapshotTotalAccrual(new BigDecimal("500"))
            .snapshotTotalAdjustment(new BigDecimal("200"))
            .build());

        byte[] xlsx = service.export(buildReq(ReportType.SUMMARY), 1L);
        XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx));
        assertEquals("SUMMARY", wb.getSheetAt(0).getSheetName());
        // header row + 3 data rows = 4 rows total
        assertTrue(wb.getSheetAt(0).getLastRowNum() >= 3);
    }

    @Test
    void exportDetail_includesInvoices() throws Exception {
        BillingInvoice inv = new BillingInvoice();
        inv.setInvoiceNo("INV001");
        inv.setStatus("L2_APPROVED");
        inv.setCounterpartyId(100L);
        inv.setCurrency("CNY");
        inv.setNetAmount(new BigDecimal("1000"));
        inv.setFxRateAtCreation(BigDecimal.ONE);
        when(invoiceMapper.selectList(any())).thenReturn(List.of(inv));

        byte[] xlsx = service.export(buildReq(ReportType.DETAIL), 1L);
        XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx));
        assertEquals("DETAIL", wb.getSheetAt(0).getSheetName());
        assertTrue(wb.getSheetAt(0).getLastRowNum() >= 1);
    }

    @Test
    void exportVariance_includesReconRows() throws Exception {
        BillingReconciliation rec = BillingReconciliation.builder()
            .period("2026-10").direction("RECEIVABLE")
            .counterpartyId(100L).currency("CNY")
            .expectedAmount(new BigDecimal("1000")).actualAmount(new BigDecimal("800"))
            .varianceAmount(new BigDecimal("200")).reconciliationStatus("ADJUSTMENT_CREATED")
            .adjustmentId(555L).build();
        when(reconMapper.selectList(any())).thenReturn(List.of(rec));

        byte[] xlsx = service.export(buildReq(ReportType.VARIANCE), 1L);
        XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx));
        assertEquals("VARIANCE", wb.getSheetAt(0).getSheetName());
        assertTrue(wb.getSheetAt(0).getLastRowNum() >= 1);
    }

    @Test
    void exportSummary_emptyData_writesNoDataRow() throws Exception {
        when(closingMapper.selectOne(any())).thenReturn(null);

        byte[] xlsx = service.export(buildReq(ReportType.SUMMARY), 1L);
        XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx));
        Row row = wb.getSheetAt(0).getRow(1);
        assertNotNull(row);
        assertEquals("No Data", row.getCell(0).getStringCellValue());
    }

    @Test
    void export_withUSD_addsCurrencySuffix() throws Exception {
        when(closingMapper.selectOne(any())).thenReturn(BillingMonthlyClosing.builder()
            .period("2026-10").snapshotCurrency("USD")
            .snapshotTotalInvoice(new BigDecimal("1000"))
            .snapshotTotalAccrual(BigDecimal.ZERO)
            .snapshotTotalAdjustment(BigDecimal.ZERO).build());

        byte[] xlsx = service.export(buildReq(ReportType.SUMMARY), 1L);
        XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx));
        // currency is in the second column of the data row
        assertEquals("USD", wb.getSheetAt(0).getRow(1).getCell(1).getStringCellValue());
    }

    private ExportRequest buildReq(ReportType type) {
        ExportRequest req = new ExportRequest();
        req.setPeriod("2026-10");
        req.setCurrency("CNY");
        req.setType(type);
        return req;
    }
}