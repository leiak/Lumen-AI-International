package com.lumen.finance.service;

import com.lumen.finance.entity.CustomerRateOverride;
import com.lumen.finance.mapper.CustomerRateOverrideMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CustomerRateOverrideService 单元测试 (Iteration 4.5B.1).
 *
 * <p>纯 Mockito 测试, 不启 Spring. 覆盖:
 * <ul>
 *   <li>{@link CustomerRateOverrideService#findActive} 命中: mapper 返回 → Optional.of</li>
 *   <li>{@link CustomerRateOverrideService#findActive} 缺失: mapper 返 null → Optional.empty</li>
 * </ul>
 */
@DisplayName("CustomerRateOverrideService 单元测试")
class CustomerRateOverrideServiceTest {

    private CustomerRateOverrideMapper mockMapper;
    private CustomerRateOverrideService service;

    private static final Long TENANT = 1001L;
    private static final Long CUSTOMER = 555L;

    @BeforeEach
    void setUp() {
        mockMapper = mock(CustomerRateOverrideMapper.class);
        service = new CustomerRateOverrideService(mockMapper);
    }

    @Test
    @DisplayName("findActive: 命中记录 → Optional.of(record)")
    void findActive_hit_returnsOptionalOf() {
        CustomerRateOverride override = new CustomerRateOverride();
        override.setId(9001L);
        override.setTenantId(TENANT);
        override.setCustomerId(CUSTOMER);
        override.setBusinessType("LAST_MILE");
        override.setBaseAmount(new BigDecimal("123.45"));
        override.setCurrency("CNY");
        override.setEffectiveFrom(LocalDate.of(2026, 10, 1));
        override.setEffectiveTo(LocalDate.of(2026, 12, 31));
        when(mockMapper.findActive(anyLong(), anyLong(), anyString(), any(LocalDate.class)))
            .thenReturn(override);

        Optional<CustomerRateOverride> result =
            service.findActive(TENANT, CUSTOMER, "LAST_MILE", LocalDate.of(2026, 10, 15));

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(9001L);
        assertThat(result.get().getBaseAmount()).isEqualByComparingTo("123.45");
        verify(mockMapper).findActive(TENANT, CUSTOMER, "LAST_MILE", LocalDate.of(2026, 10, 15));
    }

    @Test
    @DisplayName("findActive: 无匹配 → Optional.empty")
    void findActive_miss_returnsEmpty() {
        when(mockMapper.findActive(anyLong(), anyLong(), anyString(), any(LocalDate.class)))
            .thenReturn(null);

        Optional<CustomerRateOverride> result =
            service.findActive(TENANT, CUSTOMER, "LAST_MILE", LocalDate.of(2026, 10, 15));

        assertThat(result).isEmpty();
    }
}
