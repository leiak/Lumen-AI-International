package com.lumen.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.finance.entity.BillingExchangeRate;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingExchangeRateMapper;
import com.lumen.finance.rates.ExchangeRateHostProvider;
import com.lumen.finance.service.ExchangeRateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * ExchangeRateService 端到端集成测试 — fallback 路径 (Iteration 4.3.2, PR2-T2.7).
 *
 * <p>真实 MySQL (testcontainers) + 真实 Spring 上下文. Mock
 * {@link ExchangeRateHostProvider} 让其抛 FinanceException (模拟 API 不可达),
 * 验证:
 * <ol>
 *   <li>{@link ExchangeRateService#saveManual} — admin 手动录入汇率 (source=MANUAL) 能落库</li>
 *   <li>{@link ExchangeRateService#getRate} — 在 API 不可用时, 仍能从历史 MANUAL 记录
 *       取到"asOf 或之前最近一条"汇率 (no API needed), 保证业务不停摆</li>
 * </ol>
 *
 * <p><b>租户上下文说明:</b> {@code t_exchange_rate} 是 V29 引入的"跨租户共享"表 (无
 * tenant_id 列), {@link com.lumen.common.entity.MybatisPlusConfig} 的 TenantLineInnerInterceptor
 * 已显式豁免, 因此本 IT 不设 {@link com.lumen.common.tenant.TenantContext}.
 */
@DisplayName("BillingRateFallback IT")
class BillingRateFallbackIT extends IntegrationBase {

    @MockBean
    private ExchangeRateHostProvider exchangeRateHostProvider;

    @Autowired
    private BillingExchangeRateMapper exchangeRateMapper;

    @Autowired
    private ExchangeRateService exchangeRateService;

    @Test
    @DisplayName("saveManual: provider 抛 FinanceException 也不影响手动录入 → t_exchange_rate 多 1 行 (source=MANUAL)")
    void manualFallback_persistsManualEntry() {
        // 1. Mock provider 抛 FinanceException (模拟 API 不可达)
        when(exchangeRateHostProvider.fetch(eq("USD"), eq("CNY"), any()))
            .thenThrow(new FinanceException(FinanceErrorCode.RATE_SYNC_FAILED, "API unreachable"));

        // 2. 手动录入一条 USD→CNY = 7.5, date=2026-01-15
        LocalDate rateDate = LocalDate.of(2026, 1, 15);
        exchangeRateService.saveManual("USD", "CNY", new BigDecimal("7.5"), rateDate, 1001L);

        // 3. 回读 DB 验证 MANUAL 行落库
        LambdaQueryWrapper<BillingExchangeRate> q = new LambdaQueryWrapper<>();
        q.eq(BillingExchangeRate::getFromCurrency, "USD")
         .eq(BillingExchangeRate::getToCurrency, "CNY")
         .eq(BillingExchangeRate::getRateDate, rateDate)
         .eq(BillingExchangeRate::getSource, "MANUAL");
        List<BillingExchangeRate> records = exchangeRateMapper.selectList(q);
        assertThat(records).as("Expected MANUAL rate to be persisted")
            .hasSize(1);

        BillingExchangeRate first = records.get(0);
        assertThat(first.getFromCurrency()).isEqualTo("USD");
        assertThat(first.getToCurrency()).isEqualTo("CNY");
        assertThat(first.getRate()).isEqualByComparingTo(new BigDecimal("7.5"));
        assertThat(first.getRateDate()).isEqualTo(rateDate);
        assertThat(first.getSource()).isEqualTo("MANUAL");
        assertThat(first.getCreatedAt()).isNotNull();
        assertThat(first.getId()).isNotNull();
    }

    @Test
    @DisplayName("getRate: 当 API 不可用时, 从历史 MANUAL 记录取最近一条 (asOf=2026-01-15, 唯一数据是 2026-01-14 的 MANUAL)")
    void getRate_returnsLatestManual_whenApiFailed() {
        // 1. 手动录入一条 EUR→CNY = 7.6, date=2026-01-14 (yesterday)
        LocalDate yesterday = LocalDate.of(2026, 1, 14);
        exchangeRateService.saveManual("EUR", "CNY", new BigDecimal("7.6"), yesterday, 1001L);

        // 2. 同时 mock provider 抛异常 (确保 fallback 不依赖 API)
        when(exchangeRateHostProvider.fetch(eq("EUR"), eq("CNY"), any()))
            .thenThrow(new FinanceException(FinanceErrorCode.RATE_SYNC_FAILED, "API unreachable"));

        // 3. 查 2026-01-15 的 EUR→CNY 汇率: 应返回昨天的 MANUAL (7.6)
        BigDecimal result = exchangeRateService.getRate("EUR", "CNY", LocalDate.of(2026, 1, 15));
        assertThat(result).isNotNull();
        assertThat(result).isEqualByComparingTo(new BigDecimal("7.6"));

        // 4. 负向断言: 当完全无数据时抛 RATE_UNAVAILABLE (用于覆盖 getRate 的 fallback 边界)
        assertThatThrownBy(() ->
            exchangeRateService.getRate("JPY", "CNY", LocalDate.of(2026, 1, 15))
        )
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.RATE_UNAVAILABLE);
    }
}