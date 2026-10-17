package com.lumen.finance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.ClosingDtos.*;
import com.lumen.finance.engine.SnapshotCalculator;
import com.lumen.finance.entity.*;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.*;
import com.lumen.finance.service.MonthlyClosingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MonthlyClosingServiceImpl implements MonthlyClosingService {

    private final BillingMonthlyClosingMapper closingMapper;
    private final BillingInvoiceMapper invoiceMapper;
    private final BillingAccrualMapper accrualMapper;
    private final BillingAdjustmentMapper adjustmentMapper;
    private final SnapshotCalculator snapshotCalculator;

    @Override
    public BillingMonthlyClosing get(Long tenantId, String period) {
        BillingMonthlyClosing c = closingMapper.selectOne(
            new LambdaQueryWrapper<BillingMonthlyClosing>()
                .eq(BillingMonthlyClosing::getPeriod, period));
        if (c == null) throw new FinanceException(FinanceErrorCode.MONTH_NOT_FOUND);
        return c;
    }

    @Override
    public BillingMonthlyClosing getOrCreate(Long tenantId, String period) {
        BillingMonthlyClosing c = closingMapper.selectOne(
            new LambdaQueryWrapper<BillingMonthlyClosing>()
                .eq(BillingMonthlyClosing::getPeriod, period));
        if (c == null) {
            c = BillingMonthlyClosing.builder()
                .tenantId(tenantId).period(period)
                .status(FinanceConstants.CLOSING_STATUS_OPEN)
                .snapshotCurrency(FinanceConstants.CURRENCY_CNY)
                .build();
            closingMapper.insert(c);
        }
        return c;
    }

    @Override
    @Transactional
    public BillingMonthlyClosing start(Long tenantId, String period) {
        BillingMonthlyClosing c = getOrCreate(tenantId, period);
        if (!FinanceConstants.CLOSING_STATUS_OPEN.equals(c.getStatus())) {
            throw new FinanceException(FinanceErrorCode.MONTH_NOT_IN_PROGRESS);
        }
        c.setStatus(FinanceConstants.CLOSING_STATUS_IN_PROGRESS);
        recalculateSnapshotInternal(c);
        closingMapper.updateById(c);
        return c;
    }

    @Override
    @Transactional
    public BillingMonthlyClosing close(Long tenantId, String period) {
        BillingMonthlyClosing c = get(tenantId, period);
        if (!FinanceConstants.CLOSING_STATUS_IN_PROGRESS.equals(c.getStatus())) {
            throw new FinanceException(FinanceErrorCode.MONTH_NOT_IN_PROGRESS);
        }
        c.setStatus(FinanceConstants.CLOSING_STATUS_CLOSED);
        c.setClosedAt(LocalDateTime.now());
        recalculateSnapshotInternal(c);
        closingMapper.updateById(c);
        return c;
    }

    @Override
    @Transactional
    public BillingMonthlyClosing reopen(Long tenantId, String period, String reason) {
        if (reason == null || reason.length() < FinanceConstants.CLOSING_REOPEN_REASON_MIN_LEN) {
            throw new FinanceException(FinanceErrorCode.BAD_REQUEST,
                "reopen_reason 至少 " + FinanceConstants.CLOSING_REOPEN_REASON_MIN_LEN + " 字符");
        }
        BillingMonthlyClosing c = get(tenantId, period);
        c.setStatus(FinanceConstants.CLOSING_STATUS_IN_PROGRESS);
        c.setReopenReason(reason);
        c.setClosedAt(null);
        recalculateSnapshotInternal(c);
        closingMapper.updateById(c);
        return c;
    }

    @Override
    public BillingMonthlyClosing recalculateSnapshot(Long tenantId, String period) {
        BillingMonthlyClosing c = get(tenantId, period);
        recalculateSnapshotInternal(c);
        closingMapper.updateById(c);
        return c;
    }

    @Override
    public List<BillingMonthlyClosing> list(Long tenantId, int page, int size) {
        return closingMapper.selectPage(new Page<>(page, size),
            new LambdaQueryWrapper<BillingMonthlyClosing>()
                .orderByDesc(BillingMonthlyClosing::getPeriod)).getRecords();
    }

    private void recalculateSnapshotInternal(BillingMonthlyClosing c) {
        SnapshotCalculator.SnapshotResult result = snapshotCalculator.empty();
        // 4.4 简化: 暂不按 period 过滤, 全表 L2_APPROVED
        List<BillingInvoice> invoices = invoiceMapper.selectList(
            new LambdaQueryWrapper<BillingInvoice>()
                .eq(BillingInvoice::getStatus, FinanceConstants.INV_STATUS_L2_APPROVED));
        for (BillingInvoice inv : invoices) {
            BigDecimal cny = snapshotCalculator.toCny(
                inv.getNetAmount() == null ? BigDecimal.ZERO : inv.getNetAmount(),
                inv.getCurrency(), inv.getFxRateAtCreation());
            result = snapshotCalculator.addInvoice(result, cny);
        }
        c.setSnapshotTotalInvoice(result.totalInvoice());
        c.setSnapshotTotalAccrual(result.totalAccrual());
        c.setSnapshotTotalAdjustment(result.totalAdjustment());
        c.setSnapshotRecalculatedAt(LocalDateTime.now());
    }
}
