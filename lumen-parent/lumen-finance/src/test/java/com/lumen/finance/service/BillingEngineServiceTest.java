package com.lumen.finance.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.BillingEventDto;
import com.lumen.finance.engine.BillingExpressionException;
import com.lumen.finance.engine.BillingScriptEngine;
import com.lumen.finance.entity.BillingAccrual;
import com.lumen.finance.entity.BillingEventLog;
import com.lumen.finance.entity.BillingRule;
import com.lumen.finance.entity.BillingRuleItem;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingAccrualMapper;
import com.lumen.finance.mapper.BillingEventLogMapper;
import com.lumen.finance.mapper.BillingRuleItemMapper;
import com.lumen.finance.mapper.BillingRuleMapper;
import com.lumen.finance.service.impl.BillingEngineServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BillingEngineService 单元测试 (Iteration 4.3.1, PR3-T3.2).
 *
 * <p>纯 Mockito 测试，不启 Spring。所有 mapper / BillingScriptEngine 都 mock 掉.
 *
 * <p>覆盖 10 个核心场景:
 * <ol>
 *   <li>happy path: rule + items evaluated → accrual OPEN + log SUCCESS</li>
 *   <li>duplicate event (UK hit on event_log) → return null</li>
 *   <li>no rule matched → log NO_RULE + return null</li>
 *   <li>rule matched but no items → log NO_RULE + return null</li>
 *   <li>eval exception → log FAILED + OPEN_FAILED accrual + throw EXPRESSION_INVALID</li>
 *   <li>duplicate accrual (UK hit on accrual) → return null (defensive)</li>
 *   <li>invalid event payload (null tenantId) → return null</li>
 *   <li>multiple items gross sum aggregation correct</li>
 *   <li>empty env (null in DTO) → handled as empty map</li>
 *   <li>eval returns null → treated as 0, no crash</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingEngineService 单元测试")
class BillingEngineServiceTest {

    @Mock private BillingEventLogMapper eventLogMapper;
    @Mock private BillingRuleMapper ruleMapper;
    @Mock private BillingRuleItemMapper itemMapper;
    @Mock private BillingAccrualMapper accrualMapper;
    @Mock private BillingScriptEngine scriptEngine;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private BillingEngineServiceImpl service;

    private static final Long TENANT = 1001L;
    private static final String SRC = "src-evt-001";
    private static final String BIZ = FinanceConstants.BIZ_LAST_MILE;
    private static final String DIR = FinanceConstants.DIR_RECEIVABLE;

    @BeforeEach
    void setUp() {
        // Stub txManager so REQUIRES_NEW branch in accrue() works without a real DataSource.
        org.springframework.transaction.PlatformTransactionManager stubTx =
            new org.springframework.transaction.PlatformTransactionManager() {
                @Override public org.springframework.transaction.TransactionStatus getTransaction(
                        org.springframework.transaction.TransactionDefinition definition) {
                    return new org.springframework.transaction.support.SimpleTransactionStatus();
                }
                @Override public void commit(org.springframework.transaction.TransactionStatus status) {}
                @Override public void rollback(org.springframework.transaction.TransactionStatus status) {}
            };
        service = new BillingEngineServiceImpl(
            eventLogMapper, ruleMapper, itemMapper, accrualMapper,
            scriptEngine, objectMapper, stubTx);
    }

    // ---------- 1. happy path ----------

    @Test
    @DisplayName("happy path: rule + items 求值成功 → accrual OPEN + log SUCCESS")
    void accrue_happyPath_writesAccrualAndSuccessLog() {
        BillingEventDto dto = newDto(Map.of("weight", new BigDecimal("12.5")));

        when(eventLogMapper.insert(any(BillingEventLog.class))).thenAnswer(inv -> {
            BillingEventLog l = inv.getArgument(0);
            l.setId(99L);
            return 1;
        });

        BillingRule rule = ruleOf(11L, TENANT, BIZ, DIR);
        when(ruleMapper.selectOne(any(Wrapper.class))).thenReturn(rule);

        BillingRuleItem i1 = itemOf(11L, 1, "amount*1.0", "BASE");
        BillingRuleItem i2 = itemOf(11L, 2, "weight*2",   "WEIGHT");
        when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(i1, i2));

        when(scriptEngine.eval(eq("amount*1.0"), anyMap())).thenReturn(new BigDecimal("100.00"));
        when(scriptEngine.eval(eq("weight*2"),   anyMap())).thenReturn(new BigDecimal("25.00"));

        when(accrualMapper.insert(any(BillingAccrual.class))).thenAnswer(inv -> {
            BillingAccrual a = inv.getArgument(0);
            a.setId(77L);
            return 1;
        });

        BillingAccrual result = service.accrue(dto);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(77L);
        assertThat(result.getStatus()).isEqualTo(FinanceConstants.ACCRUAL_STATUS_OPEN);
        assertThat(result.getGrossAmount()).isEqualByComparingTo(new BigDecimal("125.00"));
        assertThat(result.getTaxAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getNetAmount()).isEqualByComparingTo(new BigDecimal("125.00"));
        assertThat(result.getRuleId()).isEqualTo(11L);

        verify(eventLogMapper, times(1)).insert(any(BillingEventLog.class));
        ArgumentCaptor<BillingEventLog> updCap = ArgumentCaptor.forClass(BillingEventLog.class);
        verify(eventLogMapper, times(1)).updateById(updCap.capture());
        assertThat(updCap.getValue().getProcessStatus())
            .isEqualTo(FinanceConstants.EVT_STATUS_SUCCESS);
        assertThat(updCap.getValue().getProcessedAt()).isNotNull();
    }

    // ---------- 2. duplicate event (UK hit on event_log) ----------

    @Test
    @DisplayName("duplicate event: event_log UNIQUE 命中 → 直接 return null, 不再写 accrual")
    void accrue_duplicateEvent_returnsNull() {
        BillingEventDto dto = newDto(Map.of("weight", new BigDecimal("1")));
        when(eventLogMapper.insert(any(BillingEventLog.class)))
            .thenThrow(new DuplicateKeyException("uk_event violated"));

        BillingAccrual result = service.accrue(dto);

        assertThat(result).isNull();
        verify(ruleMapper, never()).selectOne(any(Wrapper.class));
        verify(accrualMapper, never()).insert(any(BillingAccrual.class));
    }

    // ---------- 3. no rule matched ----------

    @Test
    @DisplayName("no rule matched: log NO_RULE + return null")
    void accrue_noRule_setsNoRule() {
        BillingEventDto dto = newDto(Map.of("weight", new BigDecimal("1")));
        when(eventLogMapper.insert(any(BillingEventLog.class))).thenAnswer(inv -> {
            BillingEventLog l = inv.getArgument(0);
            l.setId(99L);
            return 1;
        });
        when(ruleMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BillingAccrual result = service.accrue(dto);

        assertThat(result).isNull();
        verify(itemMapper, never()).selectList(any(Wrapper.class));
        verify(accrualMapper, never()).insert(any(BillingAccrual.class));

        ArgumentCaptor<BillingEventLog> updCap = ArgumentCaptor.forClass(BillingEventLog.class);
        verify(eventLogMapper).updateById(updCap.capture());
        assertThat(updCap.getValue().getProcessStatus())
            .isEqualTo(FinanceConstants.EVT_STATUS_NO_RULE);
        assertThat(updCap.getValue().getErrorMsg()).contains("No ACTIVE rule matched");
    }

    // ---------- 4. rule matched but no items ----------

    @Test
    @DisplayName("rule matched but no items: log NO_RULE + return null")
    void accrue_ruleWithNoItems_setsNoRule() {
        BillingEventDto dto = newDto(Map.of("weight", new BigDecimal("1")));
        when(eventLogMapper.insert(any(BillingEventLog.class))).thenAnswer(inv -> {
            BillingEventLog l = inv.getArgument(0);
            l.setId(99L);
            return 1;
        });
        BillingRule rule = ruleOf(11L, TENANT, BIZ, DIR);
        when(ruleMapper.selectOne(any(Wrapper.class))).thenReturn(rule);
        when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        BillingAccrual result = service.accrue(dto);

        assertThat(result).isNull();
        ArgumentCaptor<BillingEventLog> updCap = ArgumentCaptor.forClass(BillingEventLog.class);
        verify(eventLogMapper).updateById(updCap.capture());
        assertThat(updCap.getValue().getProcessStatus())
            .isEqualTo(FinanceConstants.EVT_STATUS_NO_RULE);
        assertThat(updCap.getValue().getErrorMsg()).contains("has no items");
        verify(accrualMapper, never()).insert(any(BillingAccrual.class));
    }

    // ---------- 5. eval exception → log FAILED + OPEN_FAILED accrual + throw EXPRESSION_INVALID ----------

    @Test
    @DisplayName("eval exception: 写 OPEN_FAILED accrual + log FAILED + 抛 EXPRESSION_INVALID")
    void accrue_evalException_writesFailedAccrualAndThrows() {
        BillingEventDto dto = newDto(Map.of("weight", new BigDecimal("1")));
        when(eventLogMapper.insert(any(BillingEventLog.class))).thenAnswer(inv -> {
            BillingEventLog l = inv.getArgument(0);
            l.setId(99L);
            return 1;
        });
        BillingRule rule = ruleOf(11L, TENANT, BIZ, DIR);
        when(ruleMapper.selectOne(any(Wrapper.class))).thenReturn(rule);

        BillingRuleItem i1 = itemOf(11L, 1, "amount*1.0", "BASE");
        BillingRuleItem i2 = itemOf(11L, 2, "weight/0",   "WEIGHT");
        when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(i1, i2));

        when(scriptEngine.eval(eq("amount*1.0"), anyMap())).thenReturn(new BigDecimal("10"));
        when(scriptEngine.eval(eq("weight/0"),   anyMap()))
            .thenThrow(new BillingExpressionException(FinanceErrorCode.EXPRESSION_INVALID,
                "Division by zero"));

        assertThatThrownBy(() -> service.accrue(dto))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.EXPRESSION_INVALID);

        ArgumentCaptor<BillingEventLog> updCap = ArgumentCaptor.forClass(BillingEventLog.class);
        verify(eventLogMapper).updateById(updCap.capture());
        assertThat(updCap.getValue().getProcessStatus())
            .isEqualTo(FinanceConstants.EVT_STATUS_FAILED);

        ArgumentCaptor<BillingAccrual> accCap = ArgumentCaptor.forClass(BillingAccrual.class);
        verify(accrualMapper, times(1)).insert(accCap.capture());
        BillingAccrual failedAcc = accCap.getValue();
        assertThat(failedAcc.getStatus())
            .isEqualTo(FinanceConstants.ACCRUAL_STATUS_OPEN_FAILED);
        assertThat(failedAcc.getGrossAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(failedAcc.getErrorMsg()).contains("Division by zero");
    }

    // ---------- 6. duplicate accrual (UK hit on accrual) ----------

    @Test
    @DisplayName("accrual UNIQUE 命中 → 静默 return null (兜底幂等)")
    void accrue_duplicateAccrual_returnsNull() {
        BillingEventDto dto = newDto(Map.of("weight", new BigDecimal("1")));
        when(eventLogMapper.insert(any(BillingEventLog.class))).thenAnswer(inv -> {
            BillingEventLog l = inv.getArgument(0);
            l.setId(99L);
            return 1;
        });
        BillingRule rule = ruleOf(11L, TENANT, BIZ, DIR);
        when(ruleMapper.selectOne(any(Wrapper.class))).thenReturn(rule);
        when(itemMapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(itemOf(11L, 1, "1.0", "BASE")));
        when(scriptEngine.eval(any(String.class), anyMap())).thenReturn(new BigDecimal("5"));
        when(accrualMapper.insert(any(BillingAccrual.class)))
            .thenThrow(new DuplicateKeyException("uk_accrual violated"));

        BillingAccrual result = service.accrue(dto);

        assertThat(result).isNull();
        verify(eventLogMapper, never()).updateById(any(BillingEventLog.class));
    }

    // ---------- 7. invalid event payload ----------

    @Test
    @DisplayName("event payload 非法 (null tenantId/sourceEventId) → 直接 return null")
    void accrue_invalidPayload_returnsNull() {
        assertThat(service.accrue(null)).isNull();
        assertThat(service.accrue(new BillingEventDto(
            null, "src", BIZ, 1L, DIR, null, FinanceConstants.CP_CUSTOMER, Map.of()
        ))).isNull();
        assertThat(service.accrue(new BillingEventDto(
            TENANT, null, BIZ, 1L, DIR, null, FinanceConstants.CP_CUSTOMER, Map.of()
        ))).isNull();

        verify(eventLogMapper, never()).insert(any(BillingEventLog.class));
    }

    // ---------- 8. multiple items aggregation ----------

    @Test
    @DisplayName("多个 item 求值 → gross = 各 partial 之和")
    void accrue_multiItems_sumsGrossCorrectly() {
        BillingEventDto dto = newDto(Map.of(
            "weight", new BigDecimal("10"),
            "distance", new BigDecimal("100")
        ));
        when(eventLogMapper.insert(any(BillingEventLog.class))).thenAnswer(inv -> {
            BillingEventLog l = inv.getArgument(0);
            l.setId(99L);
            return 1;
        });
        BillingRule rule = ruleOf(11L, TENANT, BIZ, DIR);
        when(ruleMapper.selectOne(any(Wrapper.class))).thenReturn(rule);

        BillingRuleItem i1 = itemOf(11L, 1, "amount*1.0", "BASE");
        BillingRuleItem i2 = itemOf(11L, 2, "weight*2",   "WEIGHT");
        BillingRuleItem i3 = itemOf(11L, 3, "distance*1.5", "DISTANCE");
        when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(i1, i2, i3));

        when(scriptEngine.eval(eq("amount*1.0"),   anyMap())).thenReturn(new BigDecimal("50"));
        when(scriptEngine.eval(eq("weight*2"),     anyMap())).thenReturn(new BigDecimal("20"));
        when(scriptEngine.eval(eq("distance*1.5"), anyMap())).thenReturn(new BigDecimal("150"));

        when(accrualMapper.insert(any(BillingAccrual.class))).thenAnswer(inv -> {
            BillingAccrual a = inv.getArgument(0);
            a.setId(77L);
            return 1;
        });

        BillingAccrual result = service.accrue(dto);
        assertThat(result.getGrossAmount()).isEqualByComparingTo(new BigDecimal("220"));
        assertThat(result.getNetAmount()).isEqualByComparingTo(new BigDecimal("220"));
    }

    // ---------- 9. empty env (null in DTO) ----------

    @Test
    @DisplayName("env 为 null → 防御性空 Map, 不抛 NPE")
    void accrue_nullEnv_treatedAsEmpty() {
        BillingEventDto dto = new BillingEventDto(
            TENANT, SRC, BIZ, 1L, DIR, null, FinanceConstants.CP_CUSTOMER, null);
        when(eventLogMapper.insert(any(BillingEventLog.class))).thenAnswer(inv -> {
            BillingEventLog l = inv.getArgument(0);
            l.setId(99L);
            return 1;
        });
        BillingRule rule = ruleOf(11L, TENANT, BIZ, DIR);
        when(ruleMapper.selectOne(any(Wrapper.class))).thenReturn(rule);
        when(itemMapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(itemOf(11L, 1, "1.0", "BASE")));
        when(scriptEngine.eval(any(String.class), anyMap())).thenReturn(new BigDecimal("1"));
        when(accrualMapper.insert(any(BillingAccrual.class))).thenAnswer(inv -> {
            BillingAccrual a = inv.getArgument(0);
            a.setId(77L);
            return 1;
        });

        BillingAccrual result = service.accrue(dto);
        assertThat(result).isNotNull();
        assertThat(result.getGrossAmount()).isEqualByComparingTo(BigDecimal.ONE);
    }

    // ---------- 10. eval returns null → treated as 0 ----------

    @Test
    @DisplayName("eval 返回 null → 当作 0 累加, 不抛 NPE")
    void accrue_evalReturnsNull_treatedAsZero() {
        BillingEventDto dto = newDto(Map.of("weight", new BigDecimal("1")));
        when(eventLogMapper.insert(any(BillingEventLog.class))).thenAnswer(inv -> {
            BillingEventLog l = inv.getArgument(0);
            l.setId(99L);
            return 1;
        });
        BillingRule rule = ruleOf(11L, TENANT, BIZ, DIR);
        when(ruleMapper.selectOne(any(Wrapper.class))).thenReturn(rule);

        BillingRuleItem i1 = itemOf(11L, 1, "amount*1.0", "BASE");
        BillingRuleItem i2 = itemOf(11L, 2, "weight*0",   "WEIGHT");
        when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(i1, i2));

        when(scriptEngine.eval(eq("amount*1.0"), anyMap())).thenReturn(new BigDecimal("100"));
        when(scriptEngine.eval(eq("weight*0"),   anyMap())).thenReturn(null);

        when(accrualMapper.insert(any(BillingAccrual.class))).thenAnswer(inv -> {
            BillingAccrual a = inv.getArgument(0);
            a.setId(77L);
            return 1;
        });

        BillingAccrual result = service.accrue(dto);
        assertThat(result.getGrossAmount()).isEqualByComparingTo(new BigDecimal("100"));
    }

    // ============================ helpers ============================

    private static BillingEventDto newDto(Map<String, Object> env) {
        return new BillingEventDto(TENANT, SRC, BIZ, 12345L, DIR,
            999L, FinanceConstants.CP_CUSTOMER, env);
    }

    private static BillingRule ruleOf(long id, long tenantId, String biz, String dir) {
        BillingRule r = new BillingRule();
        r.setId(id);
        r.setTenantId(tenantId);
        r.setBusinessType(biz);
        r.setDirection(dir);
        r.setCounterpartyType(FinanceConstants.CP_CUSTOMER);
        r.setCurrency(FinanceConstants.CURRENCY_CNY);
        r.setPriority(10);
        r.setStatus(FinanceConstants.RULE_STATUS_ACTIVE);
        r.setEffectiveFrom(LocalDate.of(2025, 1, 1));
        r.setVersion(0);
        return r;
    }

    private static BillingRuleItem itemOf(long ruleId, int sequence, String expression, String type) {
        BillingRuleItem i = new BillingRuleItem();
        i.setId((long) (ruleId * 100 + sequence));
        i.setRuleId(ruleId);
        i.setSequence(sequence);
        i.setItemType(type);
        i.setExpression(expression);
        i.setExpressionHash(BillingScriptEngine.normalizeHash(expression));
        i.setResultField("gross_amount");
        i.setVersion(0);
        return i;
    }
}
