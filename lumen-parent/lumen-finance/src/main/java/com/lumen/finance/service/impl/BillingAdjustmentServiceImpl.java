package com.lumen.finance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.AdjustmentDtos.CreateRequest;
import com.lumen.finance.entity.BillingAdjustment;
import com.lumen.finance.entity.BillingInvoice;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingInvoiceMapper;
import com.lumen.finance.service.AdjustmentService;
import com.lumen.finance.service.BillingAdjustmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Facade impl — resolves an optional {@code sourceInvoiceId} and delegates to
 * {@link AdjustmentService#create}.
 *
 * <p>When {@code sourceInvoiceId} is null (4.4 reconciliation case), this impl picks
 * the first L2_APPROVED invoice that matches the requested (currency) and falls back
 * to any L2_APPROVED invoice for the same tenant + currency. If none found,
 * throws {@code BAD_REQUEST} so the caller can fail the auto-adjustment gracefully.
 *
 * <p>Validation is delegated to {@link AdjustmentServiceImpl} which already enforces:
 * amount &gt; 0, reason length, source invoice tenant + status, currency match.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingAdjustmentServiceImpl implements BillingAdjustmentService {

    private final AdjustmentService adjustmentService;
    private final BillingInvoiceMapper invoiceMapper;

    @Override
    public BillingAdjustment create(Long tenantId, CreateRequest req) {
        Long sourceInvoiceId = req.getSourceInvoiceId();
        if (sourceInvoiceId == null) {
            sourceInvoiceId = resolveSourceInvoiceId(tenantId, req.getCurrency());
        }
        BigDecimal amount = req.getAmount() == null ? BigDecimal.ZERO : req.getAmount().abs();
        return adjustmentService.create(
            tenantId,
            sourceInvoiceId,
            req.getAdjustmentType(),
            amount,
            req.getReason(),
            req.getOperatorId()
        );
    }

    /**
     * Find the first L2_APPROVED invoice for the tenant matching the requested currency.
     * If currency is null, picks any L2_APPROVED invoice. Returns null when none exists;
     * callers should throw {@link FinanceErrorCode#BAD_REQUEST} in that case.
     */
    private Long resolveSourceInvoiceId(Long tenantId, String currency) {
        LambdaQueryWrapper<BillingInvoice> q = new LambdaQueryWrapper<BillingInvoice>()
            .eq(BillingInvoice::getTenantId, tenantId)
            .eq(BillingInvoice::getStatus, FinanceConstants.INV_STATUS_L2_APPROVED)
            .orderByAsc(BillingInvoice::getId);
        if (currency != null && !currency.isBlank()) {
            q.eq(BillingInvoice::getCurrency, currency);
        }
        List<BillingInvoice> rows = invoiceMapper.selectList(q);
        if (rows == null || rows.isEmpty()) {
            log.warn("resolveSourceInvoiceId: no L2_APPROVED invoice found for tenant={} currency={}",
                tenantId, currency);
            throw new FinanceException(FinanceErrorCode.BAD_REQUEST,
                "No L2_APPROVED invoice found to anchor auto-adjustment (tenant=" + tenantId
                    + ", currency=" + currency + ")");
        }
        return rows.get(0).getId();
    }
}