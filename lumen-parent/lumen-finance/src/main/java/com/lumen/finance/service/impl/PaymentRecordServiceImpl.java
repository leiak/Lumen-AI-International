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

    /**
     * 按 id 取收 / 付款流水.
     *
     * <p>显式带 tenant_id + deleted = 0 —— 租户隔离的**运行期**兜底是
     * MyBatis-Plus 的 TenantLineInnerInterceptor, 但那依赖 TenantContext 的隐式行为:
     * 一旦这张表被加进豁免名单, 或该方法在没有租户上下文 (定时任务 / MQ 消费) 的
     * 场景下被复用, 就会真的读到别家的流水。显式谓词是纵深防御。
     *
     * <p>deleted 只能手写 —— 实体没有 deleted 字段 (DDL 有), 用不了 @TableLogic。
     *
     * <p>错误码用专用的 PAYMENT_RECORD_NOT_FOUND (404_007), 与
     * BillingPaymentRecordController#get 的 javadoc 一致; 早先抛的是通用
     * NOT_FOUND (404_005), 那个码全仓没有任何地方抛过。
     */
    @Override
    public BillingPaymentRecord get(Long tenantId, Long id) {
        BillingPaymentRecord r = mapper.selectOne(new LambdaQueryWrapper<BillingPaymentRecord>()
                .eq(BillingPaymentRecord::getId, id)
                .eq(BillingPaymentRecord::getTenantId, tenantId)
                .apply("deleted = 0"));
        if (r == null) throw new FinanceException(FinanceErrorCode.PAYMENT_RECORD_NOT_FOUND);
        return r;
    }

    @Override
    public List<BillingPaymentRecord> list(Long tenantId, String period, String direction, int page, int size) {
        LambdaQueryWrapper<BillingPaymentRecord> q = new LambdaQueryWrapper<>();
        // tenant_id / deleted 与 MerchantBillingViewServiceImpl.paymentScope 保持一致 ——
        // 商家视图与管理视图看到的是同一批行, 不能一个看得到软删行另一个看不到。
        q.eq(BillingPaymentRecord::getTenantId, tenantId)
         .apply("deleted = 0");
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