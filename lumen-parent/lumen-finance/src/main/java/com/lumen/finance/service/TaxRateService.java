package com.lumen.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.finance.entity.BillingTaxRate;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingTaxRateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 税率查询 + 手动维护服务 (Iteration 4.3.2, PR2-T2.6).
 *
 * <p>对应 {@code t_tax_rate} 表. 提供
 * <ol>
 *   <li>{@link #getActiveRate} — 取指定币种在指定日期有效的税率.</li>
 *   <li>{@link #list} — 按币种过滤查询税率历史.</li>
 *   <li>{@link #create} — 新增一条税率记录.</li>
 *   <li>{@link #invalidate} — 失效 (设置 effective_to) 一条历史税率.</li>
 * </ol>
 *
 * <p>单币种维度: 不分 from/to, 税率仅作用于单一币种 (e.g. CNY 13%).
 */
@Service
@RequiredArgsConstructor
public class TaxRateService {

    private final BillingTaxRateMapper taxRateMapper;

    /**
     * 取指定币种在指定日期有效 (effective_from <= asOf AND (effective_to IS NULL OR effective_to >= asOf))
     * 的税率. 若多条同时满足, 取 effective_from 最大的 (最近的) 那条.
     *
     * @param currency 币种 (e.g. "CNY", "EUR")
     * @param asOf 截止日期 (含)
     * @return 对应的税率
     * @throws FinanceException {@code TAX_RATE_NOT_FOUND} 当完全无数据时
     */
    public BigDecimal getActiveRate(String currency, LocalDate asOf) {
        LambdaQueryWrapper<BillingTaxRate> q = new LambdaQueryWrapper<>();
        q.eq(BillingTaxRate::getCurrency, currency)
         .le(BillingTaxRate::getEffectiveFrom, asOf)
         .and(w -> w.isNull(BillingTaxRate::getEffectiveTo).or().ge(BillingTaxRate::getEffectiveTo, asOf))
         .orderByDesc(BillingTaxRate::getEffectiveFrom)
         .last("LIMIT 1");
        BillingTaxRate record = taxRateMapper.selectOne(q);
        if (record == null) {
            throw new FinanceException(FinanceErrorCode.TAX_RATE_NOT_FOUND,
                "No active tax rate for " + currency + " as of " + asOf);
        }
        return record.getTaxRate();
    }

    /**
     * 按币种过滤查询税率历史 (按 effective_from desc 排序).
     */
    public List<BillingTaxRate> list(String currency, int page, int size) {
        LambdaQueryWrapper<BillingTaxRate> q = new LambdaQueryWrapper<>();
        if (currency != null) q.eq(BillingTaxRate::getCurrency, currency);
        q.orderByDesc(BillingTaxRate::getEffectiveFrom);
        return taxRateMapper.selectList(q);
    }

    /**
     * 新增一条税率记录.
     */
    public BillingTaxRate create(String currency, BigDecimal taxRate,
                                 LocalDate effectiveFrom, LocalDate effectiveTo) {
        BillingTaxRate record = BillingTaxRate.builder()
            .currency(currency)
            .taxRate(taxRate)
            .effectiveFrom(effectiveFrom)
            .effectiveTo(effectiveTo)
            .createdAt(LocalDateTime.now())
            .build();
        taxRateMapper.insert(record);
        return record;
    }

    /**
     * 失效一条历史税率 (设置 effective_to). 后续 {@link #getActiveRate} 不会返回该条.
     *
     * @param id 税率记录 id
     * @param effectiveTo 失效日期 (含此日期及之后不再有效)
     * @throws FinanceException {@code TAX_RATE_NOT_FOUND} 当 id 不存在时
     */
    public void invalidate(Long id, LocalDate effectiveTo) {
        BillingTaxRate record = taxRateMapper.selectById(id);
        if (record == null) {
            throw new FinanceException(FinanceErrorCode.TAX_RATE_NOT_FOUND,
                "Tax rate id " + id + " not found");
        }
        record.setEffectiveTo(effectiveTo);
        taxRateMapper.updateById(record);
    }
}
