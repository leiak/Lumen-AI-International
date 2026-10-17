package com.lumen.finance.engine;

import com.lumen.finance.FinanceConstants;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class SnapshotCalculator {

    public static final int SCALE = 4;
    private static final RoundingMode RM = RoundingMode.HALF_UP;

    public BigDecimal toCny(BigDecimal amount, String currency, BigDecimal fxRate) {
        if (FinanceConstants.CURRENCY_CNY.equals(currency)) return amount;
        if (fxRate == null) throw new IllegalArgumentException("fxRate required for non-CNY");
        return amount.multiply(fxRate).setScale(SCALE, RM);
    }

    public record SnapshotResult(BigDecimal totalInvoice, BigDecimal totalAccrual, BigDecimal totalAdjustment) {}

    public SnapshotResult empty() {
        return new SnapshotResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public SnapshotResult addInvoice(SnapshotResult acc, BigDecimal amount) {
        return new SnapshotResult(acc.totalInvoice.add(amount), acc.totalAccrual, acc.totalAdjustment);
    }

    public SnapshotResult addAccrual(SnapshotResult acc, BigDecimal amount) {
        return new SnapshotResult(acc.totalInvoice, acc.totalAccrual.add(amount), acc.totalAdjustment);
    }

    public SnapshotResult addAdjustment(SnapshotResult acc, BigDecimal amount, String type) {
        BigDecimal delta = FinanceConstants.ADJ_TYPE_DEBIT.equals(type) ? amount.negate() : amount;
        return new SnapshotResult(acc.totalInvoice, acc.totalAccrual, acc.totalAdjustment.add(delta));
    }
}
