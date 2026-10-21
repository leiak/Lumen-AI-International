package com.lumen.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.finance.dto.EstimateResult;
import com.lumen.finance.engine.BillingScriptEngine;
import com.lumen.finance.entity.*;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.*;
import com.lumen.finance.service.impl.BillingEngineServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BillingEngineServiceEstimateTest {

    @Mock BillingEventLogMapper eventLogMapper;
    @Mock BillingRuleMapper ruleMapper;
    @Mock BillingRuleItemMapper itemMapper;
    @Mock BillingAccrualMapper accrualMapper;
    @Mock BillingScriptEngine scriptEngine;
    @Mock CustomerRateOverrideService overrideService;
    @Mock AccrualService accrualService;
    @Mock PlatformTransactionManager txManager;
    BillingEngineServiceImpl service;

    @BeforeEach void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new BillingEngineServiceImpl(eventLogMapper, ruleMapper, itemMapper,
            accrualMapper, scriptEngine, new ObjectMapper(), txManager, overrideService, accrualService);
    }

    @Test
    void estimate_overrideHit_returnsBaseAmountWithOverrideSource() {
        CustomerRateOverride o = new CustomerRateOverride();
        o.setId(17L); o.setBaseAmount(new BigDecimal("123.45")); o.setCurrency("CNY");
        when(overrideService.findActive(anyLong(), anyLong(), anyString(), any()))
            .thenReturn(Optional.of(o));

        EstimateResult result = service.estimate(1L, 42L, "LAST_MILE", Map.of("weight", new BigDecimal("10")));
        assertEquals(new BigDecimal("123.45"), result.amount());
        assertEquals("CNY", result.currency());
        assertEquals(EstimateResult.SOURCE_OVERRIDE, result.source());
        assertEquals(17L, result.overrideId());
        assertTrue(result.breakdown().contains("123.45"));
        assertTrue(result.breakdown().contains("17"));
    }

    @Test
    void estimate_overrideMiss_ruleHit_runsAviator_returnsRuleSource() {
        when(overrideService.findActive(anyLong(), anyLong(), anyString(), any()))
            .thenReturn(Optional.empty());

        BillingRule rule = new BillingRule();
        rule.setId(99L); rule.setCurrency("CNY");
        when(ruleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(rule);

        BillingRuleItem item1 = new BillingRuleItem();
        item1.setSequence(1); item1.setExpression("weight * 10");
        BillingRuleItem item2 = new BillingRuleItem();
        item2.setSequence(2); item2.setExpression("distance * 2");
        when(itemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(item1, item2));

        when(scriptEngine.eval(eq("weight * 10"), any()))
            .thenReturn(new BigDecimal("100.00"));
        when(scriptEngine.eval(eq("distance * 2"), any()))
            .thenReturn(new BigDecimal("23.45"));

        EstimateResult result = service.estimate(1L, 42L, "LAST_MILE", Map.of(
            "weight", new BigDecimal("10"), "distance", new BigDecimal("11.725")));
        assertEquals(new BigDecimal("123.45"), result.amount());
        assertEquals(EstimateResult.SOURCE_RULE, result.source());
        assertEquals(99L, result.ruleId());
        assertTrue(result.breakdown().contains("100.00"));
        assertTrue(result.breakdown().contains("23.45"));
    }

    @Test
    void estimate_ruleMiss_returnsNoneSource() {
        when(overrideService.findActive(anyLong(), anyLong(), anyString(), any()))
            .thenReturn(Optional.empty());
        when(ruleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        EstimateResult result = service.estimate(1L, 42L, "LAST_MILE", Map.of("weight", new BigDecimal("10")));
        assertEquals(EstimateResult.SOURCE_NONE, result.source());
        assertNull(result.amount());
        assertTrue(result.breakdown().contains("无匹配"));
    }

    @Test
    void estimate_aviatorThrows_throwsFinanceException() {
        when(overrideService.findActive(anyLong(), anyLong(), anyString(), any()))
            .thenReturn(Optional.empty());

        BillingRule rule = new BillingRule();
        rule.setId(99L); rule.setCurrency("CNY");
        when(ruleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(rule);

        BillingRuleItem item = new BillingRuleItem();
        item.setSequence(1); item.setExpression("invalid_expr_$$$");
        when(itemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(item));
        when(scriptEngine.eval(eq("invalid_expr_$$$"), any()))
            .thenThrow(new RuntimeException("bad expression"));

        assertThrows(FinanceException.class, () ->
            service.estimate(1L, 42L, "LAST_MILE", Map.of("weight", new BigDecimal("10"))));
    }

    @Test
    void estimate_overrideCurrencyUsd_returnsOverrideCurrency() {
        CustomerRateOverride o = new CustomerRateOverride();
        o.setId(18L); o.setBaseAmount(new BigDecimal("50.00")); o.setCurrency("USD");
        when(overrideService.findActive(anyLong(), anyLong(), anyString(), any()))
            .thenReturn(Optional.of(o));

        EstimateResult result = service.estimate(1L, 42L, "WAREHOUSING", Map.of());
        assertEquals("USD", result.currency());
        assertEquals(new BigDecimal("50.00"), result.amount());
        assertEquals(18L, result.overrideId());
    }
}
