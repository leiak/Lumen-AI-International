package com.lumen.finance.rates;

import com.lumen.finance.entity.BillingExchangeRate;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingExchangeRateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RateSyncService 单元测试 (Iteration 4.3.2, PR2-T2.3).
 *
 * <p>纯 Mockito 测试, 不启 Spring. 直接调用 {@link RateSyncService#syncDaily()} (不走
 * {@code @Scheduled} 触发器).
 *
 * <p>覆盖:
 * <ul>
 *   <li>syncDaily: 正常路径 — provider 返 quote → mapper.insert 收到正确字段</li>
 *   <li>syncDaily: provider 抛异常 — error 被吞掉, 不写库, 不向上抛</li>
 *   <li>manualSync: 返回 quote 且写库</li>
 * </ul>
 */
class RateSyncServiceTest {

    private RateProvider mockProvider;
    private BillingExchangeRateMapper mockMapper;
    private RateSyncService service;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        mockProvider = mock(RateProvider.class);
        mockMapper = mock(BillingExchangeRateMapper.class);
        service = new RateSyncService(mockProvider, mockMapper);
        today = LocalDate.of(2026, 1, 15);
    }

    @Test
    void syncDaily_success_insertsRecord() {
        when(mockProvider.fetch(eq("USD"), eq("CNY"), any(LocalDate.class)))
            .thenReturn(new RateProvider.RateQuote(new BigDecimal("7.1234"), today, "EXCHANGERATE_HOST"));
        service.syncDaily();

        ArgumentCaptor<BillingExchangeRate> captor = ArgumentCaptor.forClass(BillingExchangeRate.class);
        verify(mockMapper).insert(captor.capture());
        BillingExchangeRate saved = captor.getValue();
        assertEquals("USD", saved.getFromCurrency());
        assertEquals("CNY", saved.getToCurrency());
        assertEquals(0, new BigDecimal("7.1234").compareTo(saved.getRate()));
        assertEquals(today, saved.getRateDate());
        assertEquals("EXCHANGERATE_HOST", saved.getSource());
        assertNotNull(saved.getCreatedAt());
    }

    @Test
    void syncDaily_providerThrows_logsError_noRethrow() {
        when(mockProvider.fetch(eq("USD"), eq("CNY"), any(LocalDate.class)))
            .thenThrow(new FinanceException(FinanceErrorCode.RATE_SYNC_FAILED, "API down"));
        // Should not throw — error is logged and swallowed
        service.syncDaily();
        verify(mockMapper, never()).insert(any(BillingExchangeRate.class));
    }

    @Test
    void manualSync_returnsQuote() {
        RateProvider.RateQuote expected =
            new RateProvider.RateQuote(new BigDecimal("7.5"), today, "EXCHANGERATE_HOST");
        when(mockProvider.fetch(eq("USD"), eq("CNY"), any(LocalDate.class))).thenReturn(expected);
        RateProvider.RateQuote actual = service.manualSync();
        assertEquals(expected, actual);
        verify(mockMapper).insert(any(BillingExchangeRate.class));
    }
}