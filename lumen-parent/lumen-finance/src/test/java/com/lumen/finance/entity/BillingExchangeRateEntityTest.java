package com.lumen.finance.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BillingExchangeRateEntityTest {

    @Test
    void builder_populatesAllFields() {
        LocalDate today = LocalDate.of(2026, 1, 15);
        LocalDateTime now = LocalDateTime.of(2026, 1, 15, 12, 0, 0);
        BillingExchangeRate rate = BillingExchangeRate.builder()
            .id(1L)
            .fromCurrency("USD")
            .toCurrency("CNY")
            .rate(new BigDecimal("7.1234"))
            .rateDate(today)
            .source("EXCHANGERATE_HOST")
            .createdAt(now)
            .build();
        assertEquals(1L, rate.getId());
        assertEquals("USD", rate.getFromCurrency());
        assertEquals("CNY", rate.getToCurrency());
        assertEquals(new BigDecimal("7.1234"), rate.getRate());
        assertEquals(today, rate.getRateDate());
        assertEquals("EXCHANGERATE_HOST", rate.getSource());
        assertEquals(now, rate.getCreatedAt());
    }

    @Test
    void noArgsConstructor_createsEmptyInstance() {
        BillingExchangeRate rate = new BillingExchangeRate();
        assertNotNull(rate);
    }
}
