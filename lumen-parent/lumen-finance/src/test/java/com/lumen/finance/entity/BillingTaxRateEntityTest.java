package com.lumen.finance.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BillingTaxRateEntityTest {

    @Test
    void builder_populatesAllFields() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 12, 31);
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        BillingTaxRate rate = BillingTaxRate.builder()
            .id(1L)
            .currency("CNY")
            .taxRate(new BigDecimal("0.13"))
            .effectiveFrom(from)
            .effectiveTo(to)
            .createdAt(now)
            .build();
        assertEquals(1L, rate.getId());
        assertEquals("CNY", rate.getCurrency());
        assertEquals(new BigDecimal("0.13"), rate.getTaxRate());
        assertEquals(from, rate.getEffectiveFrom());
        assertEquals(to, rate.getEffectiveTo());
        assertEquals(now, rate.getCreatedAt());
    }

    @Test
    void noArgsConstructor_createsEmptyInstance() {
        BillingTaxRate rate = new BillingTaxRate();
        assertNotNull(rate);
    }
}
