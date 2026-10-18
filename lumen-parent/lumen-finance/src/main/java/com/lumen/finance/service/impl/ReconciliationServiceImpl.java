package com.lumen.finance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.AdjustmentDtos;
import com.lumen.finance.dto.ReconciliationDtos.Result;
import com.lumen.finance.entity.BillingAdjustment;
import com.lumen.finance.entity.BillingInvoice;
import com.lumen.finance.entity.BillingPaymentRecord;
import com.lumen.finance.entity.BillingReconciliation;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingInvoiceMapper;
import com.lumen.finance.mapper.BillingPaymentRecordMapper;
import com.lumen.finance.mapper.BillingReconciliationMapper;
import com.lumen.finance.service.BillingAdjustmentService;
import com.lumen.finance.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reconciliation service impl (Iteration 4.4 PR2).
 *
 * <p><b>Algorithm</b>:
 * <ol>
 *   <li>Sum L2_APPROVED invoices (full-table; 4.4 simplification, same as MonthlyClosing).</li>
 *   <li>Sum {@code t_payment_record} for the requested period.</li>
 *   <li>Group by (direction, counterpartyId, currency) tuple key.</li>
 *   <li>For each key with non-zero variance:
 *     <ul>
 *       <li>Skip if an ADJUSTMENT_CREATED row already exists (idempotent).</li>
 *       <li>Compute adjustment type:
 *         <ul>
 *           <li>RECEIVABLE: variance &gt; 0 → DEBIT (应收 &gt; 实收 → 补收);
 *                           variance &lt; 0 → CREDIT (应收 &lt; 实收 → 退款)</li>
 *           <li>PAYABLE:    variance &gt; 0 → CREDIT (应付 &gt; 实付 → 少付);
 *                           variance &lt; 0 → DEBIT (应付 &lt; 实付 → 补付)</li>
 *         </ul>
 *       </li>
 *       <li>Call {@link BillingAdjustmentService#create} to generate a DRAFT adjustment.</li>
 *       <li>Persist a {@code t_reconciliation} row linking the variance to the adjustment.</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>The {@code period} is recorded on each {@code t_reconciliation} row and used to filter
 * the payments side; it is also embedded in the auto-adjustment reason for traceability.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationServiceImpl implements ReconciliationService {

    private final BillingReconciliationMapper reconMapper;
    private final BillingInvoiceMapper invoiceMapper;
    private final BillingPaymentRecordMapper paymentMapper;
    private final BillingAdjustmentService billingAdjustmentService;

    // ---------- read ----------

    @Override
    public BillingReconciliation get(Long tenantId, Long id) {
        BillingReconciliation r = reconMapper.selectById(id);
        // Tenant scoping: a row belonging to another tenant is treated as not found
        // (returns 404, not 403, to avoid leaking existence across tenants).
        if (r == null || !tenantId.equals(r.getTenantId())) {
            throw new FinanceException(FinanceErrorCode.NOT_FOUND, "reconciliation not found");
        }
        return r;
    }

    @Override
    public List<BillingReconciliation> list(Long tenantId, String period, int page, int size) {
        LambdaQueryWrapper<BillingReconciliation> q = new LambdaQueryWrapper<>();
        if (period != null) q.eq(BillingReconciliation::getPeriod, period);
        q.orderByDesc(BillingReconciliation::getId);
        List<BillingReconciliation> records = reconMapper.selectPage(new Page<>(page, size), q).getRecords();
        return records == null ? List.of() : records;
    }

    @Override
    public List<BillingReconciliation> listByPeriod(Long tenantId, String period,
                                                     String direction, String status) {
        LambdaQueryWrapper<BillingReconciliation> q = new LambdaQueryWrapper<>();
        q.eq(BillingReconciliation::getTenantId, tenantId);
        if (period != null && !period.isBlank()) {
            q.eq(BillingReconciliation::getPeriod, period);
        }
        if (direction != null && !direction.isBlank()) {
            q.eq(BillingReconciliation::getDirection, direction);
        }
        if (status != null && !status.isBlank()) {
            q.eq(BillingReconciliation::getReconciliationStatus, status);
        }
        q.orderByAsc(BillingReconciliation::getPeriod)
            .orderByAsc(BillingReconciliation::getDirection)
            .orderByAsc(BillingReconciliation::getCounterpartyId);
        List<BillingReconciliation> rows = reconMapper.selectList(q);
        return rows == null ? List.of() : rows;
    }

    // ---------- run ----------

    @Override
    @Transactional
    public Result runReconciliation(Long tenantId, String period) {
        // 4.4 简化: 应收/应付侧按全表 L2_APPROVED 取数 (与 MonthlyClosing 一致).
        // period 仅作为 payments 侧过滤键 + reconciliation 行 period 字段.
        // Tenant scoping (PR2.4): both sides must filter by tenantId to avoid pooling
        // another tenant's L2_APPROVED invoices or payments into this tenant's reconciliation.
        List<BillingInvoice> invoices = invoiceMapper.selectList(
            new LambdaQueryWrapper<BillingInvoice>()
                .eq(BillingInvoice::getStatus, FinanceConstants.INV_STATUS_L2_APPROVED)
                .eq(BillingInvoice::getTenantId, tenantId));
        List<BillingPaymentRecord> payments = paymentMapper.selectList(
            new LambdaQueryWrapper<BillingPaymentRecord>()
                .eq(BillingPaymentRecord::getPeriod, period)
                .eq(BillingPaymentRecord::getTenantId, tenantId));

        Map<String, BigDecimal> expected = invoices.stream().collect(Collectors.toMap(
            inv -> key(inv.getDirection(), inv.getCounterpartyId(), inv.getCurrency()),
            inv -> inv.getNetAmount() == null ? BigDecimal.ZERO : inv.getNetAmount(),
            BigDecimal::add));
        Map<String, BigDecimal> actual = payments.stream().collect(Collectors.toMap(
            p -> key(p.getDirection(), p.getCounterpartyId(), p.getCurrency()),
            p -> p.getAmount() == null ? BigDecimal.ZERO : p.getAmount(),
            BigDecimal::add));

        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(expected.keySet());
        allKeys.addAll(actual.keySet());

        List<BillingReconciliation> variances = new ArrayList<>();
        List<String> autoAdjIds = new ArrayList<>();
        int varianceCount = 0;

        for (String k : allKeys) {
            BigDecimal exp = expected.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal act = actual.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal variance = exp.subtract(act);
            if (variance.signum() == 0) continue;

            String[] parts = k.split("\\|");
            String direction = parts[0];
            Long counterpartyId = Long.parseLong(parts[1]);
            String currency = parts[2];

            // 幂等: 已存在 ADJUSTMENT_CREATED 跳过
            BillingReconciliation existing = reconMapper.selectOne(
                new LambdaQueryWrapper<BillingReconciliation>()
                    .eq(BillingReconciliation::getTenantId, tenantId)
                    .eq(BillingReconciliation::getPeriod, period)
                    .eq(BillingReconciliation::getDirection, direction)
                    .eq(BillingReconciliation::getCounterpartyId, counterpartyId)
                    .eq(BillingReconciliation::getCurrency, currency));
            if (existing != null
                && FinanceConstants.RECON_STATUS_ADJUSTMENT_CREATED.equals(existing.getReconciliationStatus())) {
                log.info("runReconciliation: skip idempotent key period={} tuple={}", period, k);
                continue;
            }

            // 自动调账规则 (RECEIVABLE / PAYABLE 分支)
            String adjType;
            if (FinanceConstants.DIR_RECEIVABLE.equals(direction)) {
                adjType = variance.signum() > 0
                    ? FinanceConstants.ADJ_TYPE_DEBIT   // 应收 > 实收 → 补收
                    : FinanceConstants.ADJ_TYPE_CREDIT;  // 应收 < 实收 → 退款
            } else if (FinanceConstants.DIR_PAYABLE.equals(direction)) {
                adjType = variance.signum() > 0
                    ? FinanceConstants.ADJ_TYPE_CREDIT  // 应付 > 实付 → 少付
                    : FinanceConstants.ADJ_TYPE_DEBIT;   // 应付 < 实付 → 补付
            } else {
                log.warn("runReconciliation: unknown direction '{}' for key {}, skip", direction, k);
                continue;
            }

            // 调用 BillingAdjustmentService.create (直接传 CreateRequest, 字段名稳定)
            AdjustmentDtos.CreateRequest adjReq = new AdjustmentDtos.CreateRequest();
            adjReq.setAdjustmentType(adjType);
            adjReq.setAmount(variance.abs());
            adjReq.setCurrency(currency);
            // sourceInvoiceId 留 null: BillingAdjustmentServiceImpl 会自动 resolve 一个 L2_APPROVED 锚定单
            adjReq.setReason("对账差异自动生成: 期间 " + period + ", 方向 " + direction
                + ", 对手方 " + counterpartyId + ", 差异 " + variance + " " + currency);

            BillingAdjustment adjustment = billingAdjustmentService.create(tenantId, adjReq);
            autoAdjIds.add(String.valueOf(adjustment.getId()));

            // 写 t_reconciliation
            BillingReconciliation recon = BillingReconciliation.builder()
                .tenantId(tenantId)
                .period(period)
                .direction(direction)
                .counterpartyId(counterpartyId)
                .counterpartyType(extractCounterpartyType(payments, counterpartyId))
                .currency(currency)
                .expectedAmount(exp)
                .actualAmount(act)
                .varianceAmount(variance)
                .adjustmentId(adjustment.getId())
                .reconciliationStatus(FinanceConstants.RECON_STATUS_ADJUSTMENT_CREATED)
                .build();
            reconMapper.insert(recon);
            variances.add(recon);
            varianceCount++;
            log.info("runReconciliation: tenant={} period={} tuple={} variance={} adjId={}",
                tenantId, period, k, variance, adjustment.getId());
        }

        Result r = new Result();
        r.setPeriod(period);
        r.setTotalChecked(allKeys.size());
        r.setVarianceCount(varianceCount);
        r.setVariances(variances);
        r.setAutoAdjustmentIds(autoAdjIds);
        return r;
    }

    /**
     * Controller-facing 4-arg overload. The {@code currency} parameter is currently logged for
     * audit but not used to filter the algorithm — the canonical reconciliation groups by
     * {@code (direction, counterpartyId, currency)} tuple, so filtering at this layer would
     * require splitting the implementation. Operators can scope filtering by querying the
     * resulting rows via {@link #listByPeriod}. Returns void by design: the controller's
     * {@code R<Void>} response shape doesn't surface details; callers should use {@code /list}
     * after {@code /run} to inspect produced rows.
     */
    @Override
    @Transactional
    public void runReconciliation(Long tenantId, String period, String currency, Long userId) {
        log.info("runReconciliation: tenant={} period={} currency={} userId={}",
            tenantId, period, currency, userId);
        Result result = runReconciliation(tenantId, period);
        log.info("runReconciliation: tenant={} period={} currency={} userId={} totalChecked={} variances={}",
            tenantId, period, currency, userId, result.getTotalChecked(), result.getVarianceCount());
    }

    // ---------- resolve ----------

    @Override
    @Transactional
    public BillingReconciliation resolveReconciliation(Long tenantId, Long id, String note, Long userId) {
        BillingReconciliation r = get(tenantId, id);
        if (FinanceConstants.RECON_STATUS_RESOLVED.equals(r.getReconciliationStatus())) {
            log.info("resolveReconciliation: already RESOLVED tenant={} id={} userId={}",
                tenantId, id, userId);
            return r;
        }
        // State guard: WAIVED is a terminal state for this flow — silently flipping it to
        // RESOLVED would lose the waiver decision and the prior reason in the remark.
        if (FinanceConstants.RECON_STATUS_WAIVED.equals(r.getReconciliationStatus())) {
            throw new FinanceException(FinanceErrorCode.MONTH_NOT_IN_PROGRESS,
                "reconciliation is WAIVED, cannot resolve");
        }
        r.setReconciliationStatus(FinanceConstants.RECON_STATUS_RESOLVED);
        if (note != null && !note.isBlank()) {
            // Append rather than overwrite: preserves any prior waiver reason on the row.
            String existing = r.getRemark();
            r.setRemark((existing == null || existing.isBlank())
                ? note
                : existing + " | resolve:" + note);
        }
        reconMapper.updateById(r);
        log.info("resolveReconciliation: tenant={} id={} userId={}", tenantId, id, userId);
        return r;
    }

    // ---------- waive ----------

    @Override
    public BillingReconciliation waive(Long tenantId, Long id, String reason) {
        if (reason == null || reason.length() < FinanceConstants.ADJUSTMENT_REASON_MIN_LEN) {
            throw new FinanceException(FinanceErrorCode.BAD_REQUEST,
                "waive reason 至少 " + FinanceConstants.ADJUSTMENT_REASON_MIN_LEN + " 字符");
        }
        BillingReconciliation r = get(tenantId, id);
        // State guard: RESOLVED and WAIVED are terminal for this flow — flipping them back
        // to WAIVED would silently overwrite a previously-recorded resolution or waiver.
        String cur = r.getReconciliationStatus();
        if (FinanceConstants.RECON_STATUS_RESOLVED.equals(cur)
            || FinanceConstants.RECON_STATUS_WAIVED.equals(cur)) {
            throw new FinanceException(FinanceErrorCode.MONTH_NOT_IN_PROGRESS,
                "reconciliation is already " + cur + ", cannot waive");
        }
        r.setReconciliationStatus(FinanceConstants.RECON_STATUS_WAIVED);
        // Append rather than overwrite: matches the resolve() pattern and preserves any
        // pre-existing remark on the row.
        String prior = r.getRemark();
        String newRemark = (prior == null || prior.isBlank())
            ? reason
            : prior + " | waive:" + reason;
        r.setRemark(newRemark);
        reconMapper.updateById(r);
        log.info("waive: tenant={} id={} reason={}", tenantId, id, reason);
        return r;
    }

    /**
     * Controller-facing waive overload: delegates to {@link #waive(Long, Long, String)} and
     * logs the operator's userId so the audit trail records who approved the waiver.
     */
    @Override
    public BillingReconciliation waiveReconciliation(Long tenantId, Long id, String reason, Long userId) {
        BillingReconciliation r = waive(tenantId, id, reason);
        log.info("waiveReconciliation: tenant={} id={} userId={}", tenantId, id, userId);
        return r;
    }

    // ---------- helpers ----------

    private String key(String direction, Long counterpartyId, String currency) {
        return direction + "|" + counterpartyId + "|" + currency;
    }

    private String extractCounterpartyType(List<BillingPaymentRecord> payments, Long counterpartyId) {
        return payments.stream()
            .filter(p -> counterpartyId.equals(p.getCounterpartyId()))
            .map(BillingPaymentRecord::getCounterpartyType)
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse("CUSTOMER");
    }
}