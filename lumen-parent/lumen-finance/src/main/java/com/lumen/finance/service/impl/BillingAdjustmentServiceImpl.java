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
            sourceInvoiceId = resolveSourceInvoiceId(tenantId, req.getCurrency(), req.getCounterpartyId());
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
     * If currency is null, picks any L2_APPROVED invoice.
     *
     * <p><b>4.5C-2 — counterparty-aware anchor.</b> When {@code counterpartyId} is given,
     * the search is first narrowed to that counterparty's own RECEIVABLE invoices, so
     * the anchor can never land on another merchant's document. That matters because
     * {@code source_invoice_id} is what the merchant portal's "my adjustments" query
     * keys on: an adjustment for counterparty A anchored on counterparty B's invoice
     * would show up in B's portal, leaking A's amount and reason (the reason text
     * literally contains A's counterpartyId).
     *
     * <p><b>Fallback semantics</b>: if the narrowed search finds nothing, this falls
     * back to the original tenant-level search rather than throwing. Rationale —
     * the only production caller of this facade is the 4.4 reconciliation flow, which
     * must not start failing for merchants that have a variance but no L2_APPROVED
     * invoice of their own; and the leak itself is closed on the read side by the
     * claim table (an adjustment claimed by a recon belongs to that recon's
     * counterparty regardless of which invoice it is anchored on).
     */
    private Long resolveSourceInvoiceId(Long tenantId, String currency, Long counterpartyId) {
        if (counterpartyId != null) {
            LambdaQueryWrapper<BillingInvoice> scoped = anchorQuery(tenantId, currency)
                .eq(BillingInvoice::getCounterpartyId, counterpartyId)
                .eq(BillingInvoice::getDirection, FinanceConstants.DIR_RECEIVABLE);
            List<BillingInvoice> scopedRows = invoiceMapper.selectList(scoped);
            if (scopedRows != null && !scopedRows.isEmpty()) {
                return scopedRows.get(0).getId();
            }
            log.info("resolveSourceInvoiceId: no counterparty-scoped anchor, falling back to tenant-level "
                + "(tenant={} counterparty={} currency={})", tenantId, counterpartyId, currency);
        }
        List<BillingInvoice> rows = invoiceMapper.selectList(anchorQuery(tenantId, currency));
        if (rows == null || rows.isEmpty()) {
            log.warn("resolveSourceInvoiceId: no L2_APPROVED invoice found for tenant={} currency={}",
                tenantId, currency);
            throw new FinanceException(FinanceErrorCode.BAD_REQUEST,
                "No L2_APPROVED invoice found to anchor auto-adjustment (tenant=" + tenantId
                    + ", currency=" + currency + ")");
        }
        return rows.get(0).getId();
    }

    /** 锚点候选集: 同租户 + L2_APPROVED + (可选) 同币种, 按 id 升序取最小。 */
    private LambdaQueryWrapper<BillingInvoice> anchorQuery(Long tenantId, String currency) {
        LambdaQueryWrapper<BillingInvoice> q = new LambdaQueryWrapper<BillingInvoice>()
            .eq(BillingInvoice::getTenantId, tenantId)
            .eq(BillingInvoice::getStatus, FinanceConstants.INV_STATUS_L2_APPROVED)
            .orderByAsc(BillingInvoice::getId);
        if (currency != null && !currency.isBlank()) {
            q.eq(BillingInvoice::getCurrency, currency);
        }
        return q;
    }
}