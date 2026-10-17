package com.lumen.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.finance.entity.BillingExchangeRate;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingExchangeRateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 汇率查询 + 手动录入服务 (Iteration 4.3.2, PR2-T2.4).
 *
 * <p>对应 {@code t_exchange_rate} 表. 提供
 * <ol>
 *   <li>{@link #getRate} — 取 today 或之前最近一条 (any source). 完全无数据时抛 {@code RATE_UNAVAILABLE}.</li>
 *   <li>{@link #getRateOnDate} — 取精确日期的汇率 (Optional, 缺失返空).</li>
 *   <li>{@link #list} — 分页查询 (任意过滤组合).</li>
 *   <li>{@link #saveManual} — 手动录入 (source=MANUAL), 由 admin/finance 操作员入口调用.</li>
 * </ol>
 *
 * <p>多币种对: 表设计支持任意 from/to 组合; 本服务不限制币种, 调用方按需传入.
 *
 * <p>与 {@link com.lumen.finance.rates.RateSyncService} 协同: RateSyncService 负责
 * 远端拉取并写入 (source=EXCHANGERATE_HOST), 本服务负责查询与人工录入 (source=MANUAL).
 */
@Service
@RequiredArgsConstructor
public class ExchangeRateService {

    private final BillingExchangeRateMapper exchangeRateMapper;

    /**
     * 取 {@code asOf} 或之前最近一条汇率 (any source), 按 rateDate desc 排序后取 1 条.
     *
     * @param from 源币种 (e.g. "USD")
     * @param to 目标币种 (e.g. "CNY")
     * @param asOf 截止日期 (含)
     * @return 对应的 rate
     * @throws FinanceException {@code RATE_UNAVAILABLE} 当完全无数据时
     */
    public BigDecimal getRate(String from, String to, LocalDate asOf) {
        LambdaQueryWrapper<BillingExchangeRate> q = new LambdaQueryWrapper<>();
        q.eq(BillingExchangeRate::getFromCurrency, from)
         .eq(BillingExchangeRate::getToCurrency, to)
         .le(BillingExchangeRate::getRateDate, asOf)
         .orderByDesc(BillingExchangeRate::getRateDate)
         .last("LIMIT 1");
        BillingExchangeRate record = exchangeRateMapper.selectOne(q);
        if (record == null) {
            throw new FinanceException(FinanceErrorCode.RATE_UNAVAILABLE,
                "No exchange rate found for " + from + "->" + to + " as of " + asOf);
        }
        return record.getRate();
    }

    /**
     * 取精确日期的汇率. 缺失返 {@link Optional#empty()} (不抛异常).
     */
    public Optional<BigDecimal> getRateOnDate(String from, String to, LocalDate date) {
        LambdaQueryWrapper<BillingExchangeRate> q = new LambdaQueryWrapper<>();
        q.eq(BillingExchangeRate::getFromCurrency, from)
         .eq(BillingExchangeRate::getToCurrency, to)
         .eq(BillingExchangeRate::getRateDate, date);
        BillingExchangeRate record = exchangeRateMapper.selectOne(q);
        return Optional.ofNullable(record).map(BillingExchangeRate::getRate);
    }

    /**
     * 分页/过滤查询汇率 (from/to/fromDate/toDate 任意组合可空), 按 rateDate desc 排序.
     */
    public List<BillingExchangeRate> list(String from, String to,
                                          LocalDate fromDate, LocalDate toDate,
                                          int page, int size) {
        LambdaQueryWrapper<BillingExchangeRate> q = new LambdaQueryWrapper<>();
        if (from != null) q.eq(BillingExchangeRate::getFromCurrency, from);
        if (to != null) q.eq(BillingExchangeRate::getToCurrency, to);
        if (fromDate != null) q.ge(BillingExchangeRate::getRateDate, fromDate);
        if (toDate != null) q.le(BillingExchangeRate::getRateDate, toDate);
        q.orderByDesc(BillingExchangeRate::getRateDate);
        return exchangeRateMapper.selectList(q);
    }

    /**
     * 手动录入汇率 (source=MANUAL). 由 admin/finance 操作员触发, 通常用于:
     * <ul>
     *   <li>补充远端拉取失败的特殊日期</li>
     *   <li>覆盖/调整系统默认汇率 (合同约定)</li>
     * </ul>
     *
     * @param from 源币种
     * @param to 目标币种
     * @param rate 汇率 (1 from = rate to)
     * @param rateDate 汇率日期
     * @param operatorId 操作员 ID (留痕, 后续可写入 audit log)
     */
    public void saveManual(String from, String to, BigDecimal rate, LocalDate rateDate, Long operatorId) {
        BillingExchangeRate record = BillingExchangeRate.builder()
            .fromCurrency(from)
            .toCurrency(to)
            .rate(rate)
            .rateDate(rateDate)
            .source("MANUAL")
            .createdAt(LocalDateTime.now())
            .build();
        exchangeRateMapper.insert(record);
    }
}
