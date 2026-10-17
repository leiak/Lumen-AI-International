package com.lumen.finance.rates;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 汇率提供方接口 (Iteration 4.3.2, PR2-T2.2).
 *
 * <p>由 {@link ExchangeRateHostProvider} 等实现, 后续可扩展 OpenExchangeRates,
 * 央行公开牌价 (SAFE), 或者本地数据库缓存 (fallback) 等.</p>
 *
 * <p>返回值 {@link RateQuote} 含三要素: rate (1 from = X to), rateDate (报价对应的日期),
 * source (标识是哪个数据源拉的).</p>
 */
public interface RateProvider {

    /**
     * 拉取指定币种对的当前汇率.
     *
     * @param from 源币种 (ISO 4217, e.g. "USD")
     * @param to   目标币种 (ISO 4217, e.g. "CNY")
     * @param asOf 汇率对应的日期 (业务调用方决定的查询日期)
     * @return 报价 (rate + rateDate + source)
     * @throws com.lumen.finance.exception.FinanceException 当远端返回 success=false / 网络异常 /
     *         响应解析失败时抛出 (FinanceErrorCode.RATE_SYNC_FAILED).
     */
    RateQuote fetch(String from, String to, LocalDate asOf);

    /**
     * 报价快照: rate + rateDate + source.
     *
     * @param rate     汇率 (1 from = rate to)
     * @param rateDate 汇率对应的日期
     * @param source   数据源标识 (e.g. "EXCHANGERATE_HOST", "SAFE", "DB_CACHE")
     */
    record RateQuote(BigDecimal rate, LocalDate rateDate, String source) {}
}
