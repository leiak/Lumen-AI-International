package com.lumen.finance.rates;

import com.lumen.finance.exception.FinanceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ExchangeRateHostProvider 单元测试 (Iteration 4.3.2, PR2-T2.2).
 *
 * <p>覆盖规范:
 * <ul>
 *   <li>正常路径: 解析 success=true / rates.CNY=7.1234</li>
 *   <li>异常路径 1: success=false (e.g. invalid base) → FinanceException</li>
 *   <li>异常路径 2: HTTP 错误 / 网络异常 → FinanceException</li>
 *   <li>异常路径 3: 响应非合法 JSON → FinanceException</li>
 * </ul>
 */
class ExchangeRateHostProviderTest {

    private RestTemplate mockRest;
    private ExchangeRateHostProvider provider;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        mockRest = mock(RestTemplate.class);
        provider = new ExchangeRateHostProvider(mockRest);
        today = LocalDate.of(2026, 1, 15);
    }

    @Test
    void fetch_success_parsesResponse() {
        when(mockRest.getForObject(contains("exchangerate.host"), eq(String.class)))
            .thenReturn("{\"success\":true,\"rates\":{\"CNY\":7.1234}}");
        RateProvider.RateQuote quote = provider.fetch("USD", "CNY", today);
        assertEquals(0, new BigDecimal("7.1234").compareTo(quote.rate()));
        assertEquals(today, quote.rateDate());
        assertEquals("EXCHANGERATE_HOST", quote.source());
    }

    @Test
    void fetch_successFalse_throwsRateSyncFailed() {
        when(mockRest.getForObject(contains("exchangerate.host"), eq(String.class)))
            .thenReturn("{\"success\":false,\"error\":{\"info\":\"invalid base\"}}");
        FinanceException ex = assertThrows(FinanceException.class,
            () -> provider.fetch("XYZ", "CNY", today));
        assertTrue(ex.getMessage().contains("success=false"),
            "expected message to mention success=false but was: " + ex.getMessage());
    }

    @Test
    void fetch_httpError_throwsRateSyncFailed() {
        when(mockRest.getForObject(anyString(), eq(String.class)))
            .thenThrow(new RestClientException("connection refused"));
        assertThrows(FinanceException.class, () -> provider.fetch("USD", "CNY", today));
    }

    @Test
    void fetch_malformedJson_throwsRateSyncFailed() {
        when(mockRest.getForObject(anyString(), eq(String.class)))
            .thenReturn("not json {");
        assertThrows(FinanceException.class, () -> provider.fetch("USD", "CNY", today));
    }
}
