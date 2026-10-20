package com.lumen.finance.service;

import com.lumen.finance.entity.CustomerRateOverride;
import com.lumen.finance.mapper.CustomerRateOverrideMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 客户计费覆盖查询服务 (Iteration 4.5B.1).
 * <p>对应 {@code t_customer_rate_override} 表. 当前仅提供按 (tenant, customer, businessType, asOf)
 * 单条查询 — 用于计费引擎 (BillingEngineService) 在匹配 t_billing_rule 后做高优先级覆盖判断.
 * 写操作由后续 PR/Portal 提供 (admin 录入), 本 PR 仅留读侧.
 */
@Service
@RequiredArgsConstructor
public class CustomerRateOverrideService {

    private final CustomerRateOverrideMapper overrideMapper;

    /**
     * 查找给定 asOf 生效的客户计费覆盖 (缺失返 {@link Optional#empty()}).
     * <p>实际 SQL 排序: effective_from DESC, id DESC — 最新生效优先.
     */
    public Optional<CustomerRateOverride> findActive(Long tenantId, Long customerId,
                                                     String businessType, LocalDate asOf) {
        return Optional.ofNullable(
            overrideMapper.findActive(tenantId, customerId, businessType, asOf));
    }
}
