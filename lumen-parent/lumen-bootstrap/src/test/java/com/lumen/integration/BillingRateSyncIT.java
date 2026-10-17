package com.lumen.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.finance.entity.BillingExchangeRate;
import com.lumen.finance.mapper.BillingExchangeRateMapper;
import com.lumen.finance.rates.ExchangeRateHostProvider;
import com.lumen.finance.rates.RateProvider;
import com.lumen.finance.rates.RateSyncService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * RateSyncService 端到端集成测试 (Iteration 4.3.2, PR2-T2.7).
 *
 * <p>真实 MySQL (testcontainers) + 真实 Spring 上下文. Mock {@link ExchangeRateHostProvider}
 * (即 {@link RateProvider} 的实现) 让其返回 deterministic quote, 然后直接调
 * {@link RateSyncService#syncDaily()} (不走 {@code @Scheduled} cron 触发器), 验证:
 * <ol>
 *   <li>quote 落库 — t_exchange_rate 多 1 行 (source=EXCHANGERATE_HOST)</li>
 *   <li>字段一致性 — fromCurrency=USD, toCurrency=CNY, rate=7.1234, createdAt 非空</li>
 * </ol>
 *
 * <p><b>租户上下文说明:</b> {@code t_exchange_rate} 是 V29 引入的"跨租户共享"表 (无
 * tenant_id 列), {@link com.lumen.common.entity.MybatisPlusConfig} 的 TenantLineInnerInterceptor
 * 已显式豁免, 因此本 IT 不设 {@link com.lumen.common.tenant.TenantContext}, 也不清理 —
 * 反正该拦截器对这张表不生效.
 */
@DisplayName("BillingRateSync IT")
class BillingRateSyncIT extends IntegrationBase {

    @MockBean
    private ExchangeRateHostProvider exchangeRateHostProvider;

    @Autowired
    private BillingExchangeRateMapper exchangeRateMapper;

    @Autowired
    private RateSyncService rateSyncService;

    @Test
    @DisplayName("syncDaily: provider 返 quote → t_exchange_rate 多 1 行 (source=EXCHANGERATE_HOST)")
    void syncDaily_insertsRateFromProvider() {
        // 1. Mock provider: 固定返回 USD→CNY = 7.1234, date=2026-01-15
        LocalDate rateDate = LocalDate.of(2026, 1, 15);
        when(exchangeRateHostProvider.fetch(eq("USD"), eq("CNY"), any()))
            .thenReturn(new RateProvider.RateQuote(new BigDecimal("7.1234"), rateDate, "EXCHANGERATE_HOST"));

        // 2. 触发 sync (不走 cron, 直接调方法)
        rateSyncService.syncDaily();

        // 3. 回读 DB 验证行落库
        LambdaQueryWrapper<BillingExchangeRate> q = new LambdaQueryWrapper<>();
        q.eq(BillingExchangeRate::getFromCurrency, "USD")
         .eq(BillingExchangeRate::getToCurrency, "CNY")
         .eq(BillingExchangeRate::getRateDate, rateDate)
         .eq(BillingExchangeRate::getSource, "EXCHANGERATE_HOST");
        List<BillingExchangeRate> records = exchangeRateMapper.selectList(q);
        assertThat(records).hasSize(1);

        BillingExchangeRate first = records.get(0);
        assertThat(first.getFromCurrency()).isEqualTo("USD");
        assertThat(first.getToCurrency()).isEqualTo("CNY");
        assertThat(first.getRate()).isEqualByComparingTo(new BigDecimal("7.1234"));
        assertThat(first.getRateDate()).isEqualTo(rateDate);
        assertThat(first.getSource()).isEqualTo("EXCHANGERATE_HOST");
        assertThat(first.getCreatedAt()).isNotNull();
        assertThat(first.getId()).isNotNull();
    }
}