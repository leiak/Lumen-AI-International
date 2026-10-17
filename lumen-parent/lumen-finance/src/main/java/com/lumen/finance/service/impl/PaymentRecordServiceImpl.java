package com.lumen.finance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.PaymentRecordDtos.CreateRequest;
import com.lumen.finance.dto.PaymentRecordDtos.ImportResult;
import com.lumen.finance.entity.BillingPaymentRecord;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingPaymentRecordMapper;
import com.lumen.finance.service.PaymentRecordService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentRecordServiceImpl implements PaymentRecordService {

    private final BillingPaymentRecordMapper mapper;

    @Override
    public BillingPaymentRecord get(Long tenantId, Long id) {
        BillingPaymentRecord r = mapper.selectById(id);
        if (r == null) throw new FinanceException(FinanceErrorCode.NOT_FOUND);
        return r;
    }

    @Override
    public List<BillingPaymentRecord> list(Long tenantId, String period, String direction, int page, int size) {
        LambdaQueryWrapper<BillingPaymentRecord> q = new LambdaQueryWrapper<>();
        if (period != null) q.eq(BillingPaymentRecord::getPeriod, period);
        if (direction != null) q.eq(BillingPaymentRecord::getDirection, direction);
        q.orderByDesc(BillingPaymentRecord::getId);
        return mapper.selectPage(new Page<>(page, size), q).getRecords();
    }

    @Override
    public BillingPaymentRecord create(Long tenantId, Long userId, CreateRequest req) {
        validatePeriod(req.getPeriod());
        BillingPaymentRecord r = BillingPaymentRecord.builder()
            .tenantId(tenantId).period(req.getPeriod()).direction(req.getDirection())
            .counterpartyId(req.getCounterpartyId()).counterpartyType(req.getCounterpartyType())
            .currency(req.getCurrency()).amount(req.getAmount())
            .sourceRef(req.getSourceRef()).paymentDate(req.getPaymentDate())
            .source(FinanceConstants.PAY_SOURCE_MANUAL).remark(req.getRemark())
            .createdBy(userId)
            .build();
        try {
            mapper.insert(r);
        } catch (DuplicateKeyException e) {
            throw new FinanceException(FinanceErrorCode.PAYMENT_RECORD_DUPLICATE);
        }
        return r;
    }

    @Override
    public ImportResult importCsv(Long tenantId, String period, Long userId, MultipartFile file) {
        validatePeriod(period);
        ImportResult result = new ImportResult();
        List<String> errors = new ArrayList<>();
        int total = 0, success = 0, skipped = 0;
        try (var reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             var parser = CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(reader)) {
            for (CSVRecord rec : parser) {
                total++;
                try {
                    BillingPaymentRecord r = BillingPaymentRecord.builder()
                        .tenantId(tenantId).period(period)
                        .direction(rec.get("direction"))
                        .counterpartyId(Long.parseLong(rec.get("counterparty_id")))
                        .counterpartyType(rec.get("counterparty_type"))
                        .currency(rec.get("currency"))
                        .amount(new BigDecimal(rec.get("amount")))
                        .paymentDate(LocalDate.parse(rec.get("payment_date")))
                        .sourceRef(rec.isMapped("source_ref") ? rec.get("source_ref") : null)
                        .source(FinanceConstants.PAY_SOURCE_CSV)
                        .remark(rec.isMapped("remark") ? rec.get("remark") : null)
                        .createdBy(userId)
                        .build();
                    mapper.insert(r);
                    success++;
                } catch (DuplicateKeyException e) {
                    skipped++;
                } catch (Exception e) {
                    errors.add("Row " + rec.getRecordNumber() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            throw new FinanceException(FinanceErrorCode.BAD_REQUEST, "CSV parse failed: " + e.getMessage());
        }
        result.setTotalRows(total);
        result.setSuccessCount(success);
        result.setSkippedCount(skipped);
        result.setErrors(errors);
        return result;
    }

    private void validatePeriod(String period) {
        if (period == null || !period.matches(FinanceConstants.PERIOD_PATTERN)) {
            throw new FinanceException(FinanceErrorCode.BAD_REQUEST,
                "Invalid period format, expected YYYY-MM, got: " + period);
        }
    }
}