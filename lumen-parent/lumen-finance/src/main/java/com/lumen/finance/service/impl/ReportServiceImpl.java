package com.lumen.finance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.ReportDtos.ExportRequest;
import com.lumen.finance.dto.ReportDtos.ReportType;
import com.lumen.finance.entity.*;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.*;
import com.lumen.finance.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;
import java.io.ByteArrayOutputStream;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private final BillingMonthlyClosingMapper closingMapper;
    private final BillingInvoiceMapper invoiceMapper;
    private final BillingReconciliationMapper reconMapper;

    @Override
    public byte[] export(ExportRequest req, Long tenantId) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(100)) {
            Sheet sheet = wb.createSheet(req.getType().name());
            switch (req.getType()) {
                case SUMMARY -> writeSummary(wb, sheet, req);
                case DETAIL  -> writeDetail(wb, sheet);
                case VARIANCE -> writeVariance(wb, sheet, req);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wb.write(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new FinanceException(FinanceErrorCode.REPORT_GENERATION_FAILED, e.getMessage());
        }
    }

    private void writeSummary(SXSSFWorkbook wb, Sheet sheet, ExportRequest req) {
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Item");
        header.createCell(1).setCellValue("Currency");
        header.createCell(2).setCellValue("Amount");
        header.createCell(3).setCellValue("Period");

        BillingMonthlyClosing closing = closingMapper.selectOne(
            // PR2.4: add .eq(BillingMonthlyClosing::getTenantId, tenantId) once controllers enforce tenant scope
            new LambdaQueryWrapper<BillingMonthlyClosing>()
                .eq(BillingMonthlyClosing::getPeriod, req.getPeriod()));
        if (closing == null) {
            Row noData = sheet.createRow(1);
            noData.createCell(0).setCellValue("No Data");
            return;
        }
        int r = 1;
        addDataRow(sheet, r++, "Invoice",      closing.getSnapshotCurrency(),
            closing.getSnapshotTotalInvoice(), req.getPeriod());
        addDataRow(sheet, r++, "Accrual",      closing.getSnapshotCurrency(),
            closing.getSnapshotTotalAccrual(), req.getPeriod());
        addDataRow(sheet, r++, "Adjustment",   closing.getSnapshotCurrency(),
            closing.getSnapshotTotalAdjustment(), req.getPeriod());
    }

    private void writeDetail(SXSSFWorkbook wb, Sheet sheet) {
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Invoice No");
        header.createCell(1).setCellValue("Status");
        header.createCell(2).setCellValue("Counterparty");
        header.createCell(3).setCellValue("Currency");
        header.createCell(4).setCellValue("Net Amount");
        header.createCell(5).setCellValue("FX Rate");

        List<BillingInvoice> invoices = invoiceMapper.selectList(
            // PR2.4: add .eq(BillingInvoice::getTenantId, tenantId) once controllers enforce tenant scope
            new LambdaQueryWrapper<BillingInvoice>()
                .eq(BillingInvoice::getStatus, FinanceConstants.INV_STATUS_L2_APPROVED));
        int r = 1;
        for (BillingInvoice inv : invoices) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(inv.getInvoiceNo() == null ? "" : inv.getInvoiceNo());
            row.createCell(1).setCellValue(inv.getStatus() == null ? "" : inv.getStatus());
            row.createCell(2).setCellValue(inv.getCounterpartyId() == null ? ""
                : String.valueOf(inv.getCounterpartyId()));
            row.createCell(3).setCellValue(inv.getCurrency() == null ? "" : inv.getCurrency());
            row.createCell(4).setCellValue(inv.getNetAmount() == null ? 0
                : inv.getNetAmount().doubleValue());
            row.createCell(5).setCellValue(inv.getFxRateAtCreation() == null ? 1.0
                : inv.getFxRateAtCreation().doubleValue());
        }
    }

    private void writeVariance(SXSSFWorkbook wb, Sheet sheet, ExportRequest req) {
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Period");
        header.createCell(1).setCellValue("Direction");
        header.createCell(2).setCellValue("Counterparty");
        header.createCell(3).setCellValue("Currency");
        header.createCell(4).setCellValue("Expected");
        header.createCell(5).setCellValue("Actual");
        header.createCell(6).setCellValue("Variance");
        header.createCell(7).setCellValue("Status");
        header.createCell(8).setCellValue("Adjustment ID");

        List<BillingReconciliation> recs = reconMapper.selectList(
            // PR2.4: add .eq(BillingReconciliation::getTenantId, tenantId) once controllers enforce tenant scope
            new LambdaQueryWrapper<BillingReconciliation>()
                .eq(BillingReconciliation::getPeriod, req.getPeriod()));
        int r = 1;
        for (BillingReconciliation rec : recs) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(rec.getPeriod());
            row.createCell(1).setCellValue(rec.getDirection());
            row.createCell(2).setCellValue(String.valueOf(rec.getCounterpartyId()));
            row.createCell(3).setCellValue(rec.getCurrency());
            row.createCell(4).setCellValue(rec.getExpectedAmount() == null ? 0 : rec.getExpectedAmount().doubleValue());
            row.createCell(5).setCellValue(rec.getActualAmount() == null ? 0 : rec.getActualAmount().doubleValue());
            row.createCell(6).setCellValue(rec.getVarianceAmount() == null ? 0 : rec.getVarianceAmount().doubleValue());
            row.createCell(7).setCellValue(rec.getReconciliationStatus() == null ? "" : rec.getReconciliationStatus());
            row.createCell(8).setCellValue(rec.getAdjustmentId() == null ? "" : String.valueOf(rec.getAdjustmentId()));
        }
    }

    private void addDataRow(Sheet sheet, int row, String item, String currency, java.math.BigDecimal amount, String period) {
        Row r = sheet.createRow(row);
        r.createCell(0).setCellValue(item);
        r.createCell(1).setCellValue(currency == null ? "" : currency);
        r.createCell(2).setCellValue(amount == null ? 0 : amount.doubleValue());
        r.createCell(3).setCellValue(period);
    }
}