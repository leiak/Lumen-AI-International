package com.lumen.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumen.finance.entity.CustomerRateOverride;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * CustomerRateOverride Mapper.
 * <p>findActive 返回单个最匹配的覆盖; 业务侧以 (effectiveFrom DESC, id DESC) 排序取首条.
 */
@Mapper
public interface CustomerRateOverrideMapper extends BaseMapper<CustomerRateOverride> {

    /**
     * 查找给定 asOf 生效的客户计费覆盖.
     * <p>按 effectiveFrom DESC, id DESC 排序取第一条 — 最新生效优先.
     * MyBatis 期望单条; 多条时抛 TooManyResultsException.
     */
    @Select("""
            SELECT * FROM t_customer_rate_override
            WHERE tenant_id = #{tenantId}
              AND customer_id = #{customerId}
              AND business_type = #{businessType}
              AND deleted = 0
              AND effective_from <= #{asOf}
              AND (effective_to IS NULL OR effective_to >= #{asOf})
            ORDER BY effective_from DESC, id DESC
            LIMIT 1
            """)
    CustomerRateOverride findActive(@Param("tenantId") Long tenantId,
                                    @Param("customerId") Long customerId,
                                    @Param("businessType") String businessType,
                                    @Param("asOf") LocalDate asOf);
}
