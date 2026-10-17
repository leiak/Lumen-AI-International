package com.lumen.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.finance.entity.BillingExchangeRate;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingExchangeRateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExchangeRateService 单元测试 (Iteration 4.3.2, PR2-T2.4).
 *
 * <p>纯 Mockito 测试, 不启 Spring. 覆盖:
 * <ul>
 *   <li>{@link ExchangeRateService#getRate} 正常路径: 返 mapper 返回的 rate</li>
 *   <li>{@link ExchangeRateService#getRate} 异常路径: mapper 返 null → RATE_UNAVAILABLE</li>
 *   <li>{@link ExchangeRateService#getRateOnDate} 正常路径: 命中返 Optional[rate]</li>
 * </ul>
 */
class ExchangeRateServiceTest {

    private BillingExchangeRateMapper mockMapper;
    private ExchangeRateService service;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        mockMapper = mock(BillingExchangeRateMapper.class);
        service = new ExchangeRateService(mockMapper);
        today = LocalDate.of(2026, 1, 15);
    }

    @Test
    void getRate_returnsLatestBeforeAsOf() {
        BillingExchangeRate record = BillingExchangeRate.builder()
            .fromCurrency("USD").toCurrency("CNY")
            .rate(new BigDecimal("7.1234")).rateDate(today).source("EXCHANGERATE_HOST")
            .build();
        when(mockMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(record);

        BigDecimal result = service.getRate("USD", "CNY", today);
        assertEquals(0, new BigDecimal("7.1234").compareTo(result));
        verify(mockMapper).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    void getRate_empty_throwsRateUnavailable() {
        when(mockMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        FinanceException ex = assertThrows(FinanceException.class,
            () -> service.getRate("USD", "CNY", today));
        assertEquals(FinanceErrorCode.RATE_UNAVAILABLE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("汇率数据不可用") || ex.getMessage().contains("No exchange rate found"));
    }

    @Test
    void getRateOnDate_returnsExactOrEmpty() {
        BillingExchangeRate record = BillingExchangeRate.builder()
            .rate(new BigDecimal("7.5")).rateDate(today).source("MANUAL")
            .fromCurrency("USD").toCurrency("CNY")
            .build();
        when(mockMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(record);

        Optional<BigDecimal> result = service.getRateOnDate("USD", "CNY", today);
        assertTrue(result.isPresent());
        assertEquals(0, new BigDecimal("7.5").compareTo(result.get()));
    }
}
