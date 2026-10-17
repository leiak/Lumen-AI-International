package com.lumen.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.finance.entity.BillingTaxRate;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingTaxRateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaxRateService 单元测试 (Iteration 4.3.2, PR2-T2.6).
 *
 * <p>纯 Mockito 测试, 不启 Spring. 覆盖:
 * <ul>
 *   <li>{@link TaxRateService#getActiveRate} 正常路径: 返 mapper 返回的税率</li>
 *   <li>{@link TaxRateService#getActiveRate} 异常路径: mapper 返 null → TAX_RATE_NOT_FOUND</li>
 * </ul>
 */
class TaxRateServiceTest {

    private BillingTaxRateMapper mockMapper;
    private TaxRateService service;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        mockMapper = mock(BillingTaxRateMapper.class);
        service = new TaxRateService(mockMapper);
        today = LocalDate.of(2026, 1, 15);
    }

    @Test
    void getActiveRate_findsCurrentRecord() {
        BillingTaxRate record = BillingTaxRate.builder()
            .id(1L).currency("CNY").taxRate(new BigDecimal("0.1300"))
            .effectiveFrom(LocalDate.of(2026, 1, 1)).effectiveTo(null)
            .build();
        when(mockMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(record);

        BigDecimal rate = service.getActiveRate("CNY", today);
        assertEquals(0, new BigDecimal("0.1300").compareTo(rate));
        verify(mockMapper).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    void getActiveRate_empty_throwsTaxRateNotFound() {
        when(mockMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        FinanceException ex = assertThrows(FinanceException.class,
            () -> service.getActiveRate("EUR", today));
        assertEquals(FinanceErrorCode.TAX_RATE_NOT_FOUND, ex.getErrorCode());
    }
}
