package com.lumen.finance.rates;

import com.lumen.finance.entity.BillingExchangeRate;
import com.lumen.finance.mapper.BillingExchangeRateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 汇率同步服务 (Iteration 4.3.2, PR2-T2.3).
 *
 * <p><b>职责</b>: 定时拉取 USD→CNY 汇率并持久化到 {@code t_exchange_rate}, 同时提供
 * 手动触发入口供 admin 端即时刷新.
 *
 * <p><b>cron 调度</b>: 每日 00:30 触发 {@link #syncDaily()}. 异常被吞掉 — log error 后
 * 不抛, 不阻塞其他 cron; 监控告警留到 4.3.3 阶段统一接入.
 *
 * <p><b>@EnableScheduling</b>: 本服务假设 {@code lumen-finance} 或 {@code lumen-bootstrap}
 * 的 SpringBootApplication 已开启 {@code @EnableScheduling}. 4.3.2 阶段该注解属于 bootstrap
 * 范围, 不在本类声明.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateSyncService {

    private final RateProvider rateProvider;
    private final BillingExchangeRateMapper exchangeRateMapper;

    /**
     * 每日 00:30 拉取 USD→CNY 汇率.
     *
     * <p>失败时 log error + 告警 (4.3.2 不做监控), 不阻塞其他 cron.
     */
    @Scheduled(cron = "0 30 0 * * ?")
    public void syncDaily() {
        try {
            RateProvider.RateQuote quote = rateProvider.fetch("USD", "CNY", LocalDate.now());
            save(quote, "EXCHANGERATE_HOST");
            log.info("Rate synced: 1 USD = {} CNY (date={})", quote.rate(), quote.rateDate());
        } catch (Exception e) {
            log.error("Daily rate sync failed: {}", e.getMessage(), e);
            // 4.3.2 不抛, 不阻塞其他 cron; 4.3.3 加监控告警
        }
    }

    /**
     * 手动触发 (admin 入口).
     *
     * @return 拉取到的报价 (rate + rateDate + source)
     * @throws com.lumen.finance.exception.FinanceException 当远端拉取失败时抛出 (FinanceErrorCode.RATE_SYNC_FAILED)
     */
    public RateProvider.RateQuote manualSync() {
        RateProvider.RateQuote quote = rateProvider.fetch("USD", "CNY", LocalDate.now());
        save(quote, "EXCHANGERATE_HOST");
        return quote;
    }

    /**
     * 将报价写入 {@code t_exchange_rate}. 公开以便测试验证构造字段, 也便于后续扩展
     * 多币种对时复用 (e.g. EUR→CNY).
     */
    public void save(RateProvider.RateQuote quote, String source) {
        BillingExchangeRate record = BillingExchangeRate.builder()
            .fromCurrency("USD")
            .toCurrency("CNY")
            .rate(quote.rate())
            .rateDate(quote.rateDate())
            .source(source)
            .createdAt(LocalDateTime.now())
            .build();
        exchangeRateMapper.insert(record);
    }
}