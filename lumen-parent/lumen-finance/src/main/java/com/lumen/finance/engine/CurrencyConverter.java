package com.lumen.finance.engine;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 4.3.2 multi-currency + tax 数学统一计算 (Iteration 4.3.2 PR2-T2.1).
 *
 * <p>含税价/不含税价 风格 (CNY 财务惯例): amount 含税, tax = amount * rate / (1+rate), net = amount - tax.</p>
 *
 * <p>RoundingMode.HALF_UP, 4 位小数 (与 t_billing_invoice / t_billing_adjustment 列精度一致).</p>
 */
@Component
public class CurrencyConverter {

    public static final int SCALE = 4;

    /**
     * 含税价 → 拆税. tax = amount * rate / (1+rate).
     */
    public static BigDecimal calculateTax(BigDecimal grossAmount, BigDecimal taxRate) {
        if (grossAmount == null || taxRate == null) {
            throw new IllegalArgumentException("amount and taxRate required");
        }
        if (taxRate.signum() == 0) return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal denominator = BigDecimal.ONE.add(taxRate);
        return grossAmount.multiply(taxRate).divide(denominator, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 拆含税价为 [tax, net].
     */
    public static BigDecimal[] splitGross(BigDecimal grossAmount, BigDecimal taxRate) {
        BigDecimal tax = calculateTax(grossAmount, taxRate);
        BigDecimal net = grossAmount.subtract(tax).setScale(SCALE, RoundingMode.HALF_UP);
        return new BigDecimal[]{tax, net};
    }

    /**
     * 跨币种转换. fxRate = 1 from = X to.
     */
    public static BigDecimal convert(BigDecimal amount, String from, String to, BigDecimal fxRate) {
        if (amount == null || fxRate == null) {
            throw new IllegalArgumentException("amount and fxRate required");
        }
        if (from.equals(to)) return amount.setScale(SCALE, RoundingMode.HALF_UP);
        return amount.multiply(fxRate).setScale(SCALE, RoundingMode.HALF_UP);
    }
}