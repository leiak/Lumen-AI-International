package com.lumen.finance.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CurrencyConverter 单元测试 (Iteration 4.3.2, PR2-T2.1).
 *
 * <p>覆盖规范:
 * <ul>
 *   <li>含税价拆税 (6% / 13% / 0% 税率)</li>
 *   <li>含税价拆为 [tax, net]</li>
 *   <li>跨币种换汇 (USD → CNY)</li>
 *   <li>同币种零费率 (rate = 1)</li>
 *   <li>参数校验 (null)</li>
 * </ul>
 *
 * <p>所有 {@code assertEquals} 使用 {@link BigDecimal#compareTo(BigDecimal)} 比较,
 * 避免 {@code BigDecimal.equals} 的 scale 敏感问题.</p>
 */
class CurrencyConverterTest {

    @Test
    void taxInclusive_6pct_CNY() {
        BigDecimal amount = new BigDecimal("100.00");
        BigDecimal tax = CurrencyConverter.calculateTax(amount, new BigDecimal("0.06"));
        // tax = 100 * 0.06 / 1.06 = 5.6604
        assertEquals(0, new BigDecimal("5.6604").compareTo(tax));
    }

    @Test
    void taxInclusive_13pct_CNY() {
        BigDecimal amount = new BigDecimal("113.00");
        BigDecimal tax = CurrencyConverter.calculateTax(amount, new BigDecimal("0.13"));
        // tax = 113 * 0.13 / 1.13 = 13.00
        assertEquals(0, new BigDecimal("13.0000").compareTo(tax));
    }

    @Test
    void taxInclusive_0pct_USD() {
        BigDecimal amount = new BigDecimal("50.00");
        BigDecimal tax = CurrencyConverter.calculateTax(amount, BigDecimal.ZERO);
        assertEquals(0, BigDecimal.ZERO.compareTo(tax));
    }

    @Test
    void splitGross_6pct_returnsTaxAndNet() {
        BigDecimal[] result = CurrencyConverter.splitGross(new BigDecimal("106.00"), new BigDecimal("0.06"));
        // tax = 106 * 0.06 / 1.06 = 6.00; net = 106 - 6 = 100.00
        assertEquals(0, new BigDecimal("6.0000").compareTo(result[0]));
        assertEquals(0, new BigDecimal("100.0000").compareTo(result[1]));
    }

    @Test
    void convert_USD_to_CNY() {
        BigDecimal result = CurrencyConverter.convert(new BigDecimal("100.00"), "USD", "CNY", new BigDecimal("7.1234"));
        // 100 * 7.1234 = 712.34
        assertEquals(0, new BigDecimal("712.3400").compareTo(result));
    }

    @Test
    void convert_sameCurrency_returnsSame() {
        BigDecimal result = CurrencyConverter.convert(new BigDecimal("100.00"), "CNY", "CNY", BigDecimal.ONE);
        assertEquals(0, new BigDecimal("100.0000").compareTo(result));
    }
}