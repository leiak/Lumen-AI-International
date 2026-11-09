package com.lumen.finance.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
// BillingReversalResult 是 BillingEngineService 的嵌套 record, 同包下简名也要 import 才能取到.
import com.lumen.finance.service.BillingEngineService.BillingReversalResult;
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
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BillingEngineService 单元测试.
 *
 * <p>纯 Mockito 测试，不启 Spring。所有 mapper / BillingScriptEngine 都 mock 掉.
 *
 * <p>覆盖 accrue / estimate / estimateAndAccrue / assertReversible 的正常与异常路径；
 * 具体场景以各用例的 {@code @DisplayName} 为准（此处不再逐条罗列，避免与用例脱节）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingEngineService 单元测试")
class BillingEngineServiceTest {

    @Mock private BillingEventLogMapper eventLogMapper;
    @Mock private BillingRuleMapper ruleMapper;
    @Mock private BillingRuleItemMapper itemMapper;
    @Mock private BillingAccrualMapper accrualMapper;
    @Mock private BillingScriptEngine scriptEngine;
    @Mock private CustomerRateOverrideService overrideService;
    @Mock private AccrualService accrualService;

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
            scriptEngine, objectMapper, stubTx, overrideService, accrualService);
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

    // ---------- 4.5D-1 A3: 取证写库失败不能顶掉 FinanceException ----------

    @Test
    @DisplayName("失败取证写库失败 → 仍抛 FinanceException (不能被 DB 异常顶掉)")
    void accrue_failureAuditWriteFails_stillThrowsFinanceException() {
        BillingEventDto dto = newDto(Map.of("weight", new BigDecimal("1")));
        when(eventLogMapper.insert(any(BillingEventLog.class))).thenAnswer(inv -> {
            BillingEventLog l = inv.getArgument(0);
            l.setId(99L);
            return 1;
        });
        BillingRule rule = ruleOf(11L, TENANT, BIZ, DIR);
        when(ruleMapper.selectOne(any(Wrapper.class))).thenReturn(rule);

        BillingRuleItem i1 = itemOf(11L, 1, "weight/0", "WEIGHT");
        when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(i1));

        when(scriptEngine.eval(eq("weight/0"), anyMap()))
            .thenThrow(new BillingExpressionException(FinanceErrorCode.EXPRESSION_INVALID,
                "Division by zero"));
        // 取证写库失败 —— 模拟 DB 挂了
        when(accrualMapper.insert(any(BillingAccrual.class)))
            .thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> service.accrue(dto))
            .as("取证写库失败不能顶掉 FinanceException —— accrue 的接口契约是「求值失败抛 "
              + "FinanceException(EXPRESSION_INVALID)」, 顶掉它就是异常类型失真: "
              + "日志/诊断里分不清「规则求值失败」和「数据库挂了」")
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.EXPRESSION_INVALID);
    }

    // ---------- 4.5D-1 A3: event_log 标 FAILED 这一步写库失败也要保护 ----------

    @Test
    @DisplayName("event_log 标 FAILED 写库失败 → 仍抛 FinanceException, 且 OPEN_FAILED 取证行照样写出")
    void accrue_eventLogWriteFails_stillThrowsFinanceExceptionAndWritesEvidenceRow() {
        BillingEventDto dto = newDto(Map.of("weight", new BigDecimal("1")));
        when(eventLogMapper.insert(any(BillingEventLog.class))).thenAnswer(inv -> {
            BillingEventLog l = inv.getArgument(0);
            l.setId(99L);
            return 1;
        });
        BillingRule rule = ruleOf(11L, TENANT, BIZ, DIR);
        when(ruleMapper.selectOne(any(Wrapper.class))).thenReturn(rule);

        BillingRuleItem i1 = itemOf(11L, 1, "weight/0", "WEIGHT");
        when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(i1));

        when(scriptEngine.eval(eq("weight/0"), anyMap()))
            .thenThrow(new BillingExpressionException(FinanceErrorCode.EXPRESSION_INVALID,
                "Division by zero"));
        // event_log 标 FAILED 这一步写库失败 —— 模拟 DB 抖动 / 连接被断
        when(eventLogMapper.updateById(any(BillingEventLog.class)))
            .thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> service.accrue(dto))
            .as("event_log 写库失败不能顶掉 FinanceException —— accrue 的接口契约是「求值失败抛 "
              + "FinanceException(EXPRESSION_INVALID)」")
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.EXPRESSION_INVALID);

        // 取证行必须照样写进去 —— event_log 写失败不能连累 accrual 的 OPEN_FAILED 行
        // (那行在 REQUIRES_NEW 的另一条连接上, 与 event_log 的成败无关)。
        ArgumentCaptor<BillingAccrual> accCap = ArgumentCaptor.forClass(BillingAccrual.class);
        verify(accrualMapper, times(1)).insert(accCap.capture());
        assertThat(accCap.getValue().getStatus())
            .isEqualTo(FinanceConstants.ACCRUAL_STATUS_OPEN_FAILED);
        assertThat(accCap.getValue().getErrorMsg()).contains("Division by zero");
    }

    // ---------- 4.5D-1 A3: estimateAndAccrue 的失败取证写库失败也不得顶掉调用方 ----------

    @Test
    @DisplayName("estimateAndAccrue 取证写库失败 → 仍然不抛 (契约是失败不抛)")
    void estimateAndAccrue_failureAuditWriteFails_doesNotThrow() {
        BillingEventDto dto = newDto(Map.of("weight", new BigDecimal("1")));
        BillingRule rule = ruleOf(11L, TENANT, BIZ, DIR);
        when(ruleMapper.selectOne(any(Wrapper.class))).thenReturn(rule);

        BillingRuleItem i1 = itemOf(11L, 1, "weight/0", "WEIGHT");
        when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(i1));

        when(scriptEngine.eval(eq("weight/0"), anyMap()))
            .thenThrow(new BillingExpressionException(FinanceErrorCode.EXPRESSION_INVALID,
                "Division by zero"));
        // 取证写库失败 —— 模拟 DB 挂了
        when(accrualMapper.insert(any(BillingAccrual.class)))
            .thenThrow(new RuntimeException("DB down"));

        assertThatCode(() -> service.estimateAndAccrue(dto))
            .as("estimateAndAccrue 的契约是「失败不抛」 —— 取证写库的异常不能顶给调用方")
            .doesNotThrowAnyException();
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

    // ---------- 11. 4.5C-1 assertReversible 预检 ----------

    @Test
    @DisplayName("assertReversible: accrual 查不到 → 放行 (无应计可冲销)")
    void assertReversible_noAccrual_passes() {
        when(accrualMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThatCode(() -> service.assertReversible(TENANT, SRC)).doesNotThrowAnyException();
        // 空实现 (直接 return) 根本不会碰 mapper, 这行专杀那个变异体
        verify(accrualMapper).selectOne(any(Wrapper.class));
    }

    @Test
    @DisplayName("assertReversible: OPEN accrual → 放行")
    void assertReversible_openAccrual_passes() {
        BillingAccrual a = new BillingAccrual();
        a.setId(900L);
        a.setTenantId(TENANT);
        a.setStatus(FinanceConstants.ACCRUAL_STATUS_OPEN);
        when(accrualMapper.selectOne(any(Wrapper.class))).thenReturn(a);

        assertThatCode(() -> service.assertReversible(TENANT, SRC)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("assertReversible: CONVERTED accrual → 抛 ACCRUAL_ALREADY_CONVERTED")
    void assertReversible_convertedAccrual_throwsAlreadyConverted() {
        BillingAccrual a = new BillingAccrual();
        a.setId(901L);
        a.setTenantId(TENANT);
        a.setStatus(FinanceConstants.ACCRUAL_STATUS_CONVERTED);
        when(accrualMapper.selectOne(any(Wrapper.class))).thenReturn(a);

        assertThatThrownBy(() -> service.assertReversible(TENANT, SRC))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.ACCRUAL_ALREADY_CONVERTED);
    }

    @Test
    @DisplayName("assertReversible: sourceEventId 为 null → 记 warn 并放行, 不打 DB")
    void assertReversible_nullSourceEventId_passesWithoutQuery() {
        assertThatCode(() -> service.assertReversible(TENANT, null)).doesNotThrowAnyException();
        // null 会让 SQL 变成 source_event_id = NULL (MySQL 恒不匹配), 静默放行 ——
        // 提前拦下, 至少留一条 warn 可查。
        verify(accrualMapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    @DisplayName("assertReversible: tenantId 为 null → 记 warn 并放行, 不打 DB")
    void assertReversible_nullTenantId_passesWithoutQuery() {
        assertThatCode(() -> service.assertReversible(null, SRC)).doesNotThrowAnyException();
        // 同 sourceEventId: tenant_id = NULL 在 MySQL 恒不匹配, 同样是静默放行 ——
        // 与 accrue 的 guard 保持对称, 两个入参一起挡。
        verify(accrualMapper, never()).selectOne(any(Wrapper.class));
    }

    // ---------- 12. 4.5C-1 reversalForSourceEventId 冲销分派 ----------

    @Test
    @DisplayName("reversal: sourceEventId 为 null → 记 warn 并返 NOT_FOUND, 不打 DB")
    void reversal_nullSourceEventId_returnsNotFound() {
        var result = service.reversalForSourceEventId(TENANT, null, "portal cancel");

        assertThat(result.outcome()).isEqualTo(BillingReversalResult.OUTCOME_NOT_FOUND);
        assertThat(result.accrualId()).isNull();
        // null 会让 SQL 变成 source_event_id = NULL (MySQL 恒不匹配), 静默放行 ——
        // 提前拦下, 否则会查一条空记录然后误返 NOT_FOUND, 而且没有 warn 可查.
        verify(accrualMapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    @DisplayName("reversal: tenantId 为 null → 记 warn 并返 NOT_FOUND, 不打 DB")
    void reversal_nullTenantId_returnsNotFound() {
        var result = service.reversalForSourceEventId(null, SRC, "portal cancel");

        assertThat(result.outcome()).isEqualTo(BillingReversalResult.OUTCOME_NOT_FOUND);
        assertThat(result.accrualId()).isNull();
        // 同 sourceEventId: tenant_id = NULL 在 MySQL 恒不匹配, 同样是静默错误答案.
        // 与 accrualService 的 guard 保持对称, 两个入参一起挡。
        verify(accrualMapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    @DisplayName("reversal: 查不到 accrual → NOT_FOUND, 不调 release")
    void reversal_noAccrual_returnsNotFound() {
        when(accrualMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        var result = service.reversalForSourceEventId(TENANT, SRC, "portal cancel");

        assertThat(result.outcome()).isEqualTo(BillingReversalResult.OUTCOME_NOT_FOUND);
        assertThat(result.accrualId()).isNull();
        verify(accrualService, never()).release(anyLong(), anyLong(), anyString(), isNull());
    }

    @Test
    @DisplayName("reversal: OPEN accrual → RELEASED + 调 accrualService.release(tenantId, id, reason, null)")
    void reversal_openAccrual_releases() {
        BillingAccrual a = accrual(902L, FinanceConstants.ACCRUAL_STATUS_OPEN);
        when(accrualMapper.selectOne(any(Wrapper.class))).thenReturn(a);

        var result = service.reversalForSourceEventId(TENANT, SRC, "portal cancel");

        verify(accrualService, times(1)).release(TENANT, 902L, "portal cancel", null);
        assertThat(result.outcome()).isEqualTo(BillingReversalResult.OUTCOME_RELEASED);
        assertThat(result.accrualId()).isEqualTo(902L);
    }

    @Test
    @DisplayName("reversal: OPEN_FAILED accrual → RELEASED (靠 V38 那条边)")
    void reversal_openFailedAccrual_releases() {
        BillingAccrual a = accrual(903L, FinanceConstants.ACCRUAL_STATUS_OPEN_FAILED);
        when(accrualMapper.selectOne(any(Wrapper.class))).thenReturn(a);

        var result = service.reversalForSourceEventId(TENANT, SRC, "portal cancel");

        verify(accrualService, times(1)).release(TENANT, 903L, "portal cancel", null);
        assertThat(result.outcome()).isEqualTo(BillingReversalResult.OUTCOME_RELEASED);
    }

    @Test
    @DisplayName("reversal: RELEASED accrual → ALREADY_RELEASED 幂等跳过, 不调 release")
    void reversal_alreadyReleased_isIdempotentSkip() {
        BillingAccrual a = accrual(904L, FinanceConstants.ACCRUAL_STATUS_RELEASED);
        when(accrualMapper.selectOne(any(Wrapper.class))).thenReturn(a);

        var result = service.reversalForSourceEventId(TENANT, SRC, "portal cancel");

        verify(accrualService, never()).release(anyLong(), anyLong(), anyString(), isNull());
        assertThat(result.outcome()).isEqualTo(BillingReversalResult.OUTCOME_ALREADY_RELEASED);
        assertThat(result.accrualId()).isEqualTo(904L);
    }

    @Test
    @DisplayName("reversal: CONVERTED accrual → 抛 ACCRUAL_ALREADY_CONVERTED, 不调 release")
    void reversal_convertedAccrual_throwsAlreadyConverted() {
        BillingAccrual a = accrual(905L, FinanceConstants.ACCRUAL_STATUS_CONVERTED);
        when(accrualMapper.selectOne(any(Wrapper.class))).thenReturn(a);

        assertThatThrownBy(() -> service.reversalForSourceEventId(TENANT, SRC, "portal cancel"))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.ACCRUAL_ALREADY_CONVERTED);
        verify(accrualService, never()).release(anyLong(), anyLong(), anyString(), isNull());
    }

    @Test
    @DisplayName("reversal: LOCKED accrual → 抛 ACCRUAL_ALREADY_CONVERTED, 不调 release")
    void reversal_lockedAccrual_throwsAlreadyConverted() {
        BillingAccrual a = accrual(906L, FinanceConstants.ACCRUAL_STATUS_LOCKED);
        when(accrualMapper.selectOne(any(Wrapper.class))).thenReturn(a);

        assertThatThrownBy(() -> service.reversalForSourceEventId(TENANT, SRC, "portal cancel"))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.ACCRUAL_ALREADY_CONVERTED);
        verify(accrualService, never()).release(anyLong(), anyLong(), anyString(), isNull());
    }

    @Test
    @DisplayName("reversal: OUTCOME_* 常量与字面量一致 (outcome 是跨模块契约, 不能被手滑改掉)")
    void reversal_outcomeConstants_matchLiterals() {
        // 钉字面量: OUTCOME_* 被 Task 5 IT / 对账 job 任何按 "RELEASED" 过滤的查询
        // 都依赖这个字面值, 手滑写错会让全套行为测试继续绿但下游链路爆雷。
        assertThat(BillingReversalResult.OUTCOME_RELEASED).isEqualTo("RELEASED");
        assertThat(BillingReversalResult.OUTCOME_ALREADY_RELEASED).isEqualTo("ALREADY_RELEASED");
        assertThat(BillingReversalResult.OUTCOME_NOT_FOUND).isEqualTo("NOT_FOUND");
    }

    // ---------- 4.5D-1 A5: estimateAndAccrue 的事务边界护栏 ----------

    /**
     * 反射断言: 实现类上确实带着 {@code @Transactional}, 且 Spring 认它、传播语义是 REQUIRED.
     *
     * <p><b>为什么只能是结构断言</b> —— 本文件是纯 Mockito 单测 (不起 Spring 容器),
     * 「事务」这个概念在这里根本不存在: 去掉 {@code @Transactional} 之后, 本文件<b>其余</b>用例
     * (它们只关心 mapper 的调用序列与返回值) <b>依然全绿</b>。所以「注解在不在 / 传播语义
     * 对不对」只能靠反射钉住。
     *
     * <p>真正跑代理、断言「无外层事务时它自己开了一个事务」的那条在
     * {@link #estimateAndAccrue_startsOwnTransaction_whenNoOuterTransaction()}。
     */
    @Test
    @DisplayName("estimateAndAccrue 带 @Transactional —— 计费引擎对外入口的事务边界护栏")
    void estimateAndAccrue_hasTransactionalAnnotation() throws Exception {
        Method m = BillingEngineServiceImpl.class.getMethod("estimateAndAccrue", BillingEventDto.class);
        assertThat(m.isAnnotationPresent(Transactional.class))
            .as("estimateAndAccrue 必须带 @Transactional —— 它是计费引擎的对外入口, "
              + "将来若有人从非事务上下文调用它, 没有这行注解 accrue 的三次写入就会退化成"
              + "逐语句自动提交。")
            .isTrue();
    }

    @Test
    @DisplayName("estimateAndAccrue 的传播语义是 REQUIRED —— 改成 REQUIRES_NEW 就红")
    void estimateAndAccrue_propagationIsRequired() throws Exception {
        TransactionAttributeSource tas = new AnnotationTransactionAttributeSource();
        // 传「接口方法 + 实现类」: Spring 的事务属性解析是「先查目标类方法, 目标类方法上没有
        // 才回退查接口方法」—— 注解必须落在实现类上, 这条断言顺带把这一点钉住。
        TransactionAttribute attr = tas.getTransactionAttribute(
            BillingEngineService.class.getMethod("estimateAndAccrue", BillingEventDto.class),
            BillingEngineServiceImpl.class);
        assertThat(attr)
            .as("estimateAndAccrue 必须被 Spring 认成事务方法 (注解要落在实现类上)")
            .isNotNull();
        assertThat(attr.getPropagationBehavior())
            .as("传播语义必须是 REQUIRED —— 改成 REQUIRES_NEW 会让 accrue 脱离调用方事务, "
              + "「accrue 与调用方同一事务」这个前提就断了")
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Test
    @DisplayName("estimateAndAccrue 在没有外层事务时自己开一个事务 (去掉 @Transactional 这条就红)")
    void estimateAndAccrue_startsOwnTransaction_whenNoOuterTransaction() {
        CountingTx tx = new CountingTx();
        BillingEngineServiceImpl target = new BillingEngineServiceImpl(
            eventLogMapper, ruleMapper, itemMapper, accrualMapper,
            scriptEngine, objectMapper, tx, overrideService, accrualService);
        // 走真实的 Spring 事务拦截器 (TransactionInterceptor + 注解属性解析),
        // 只是把事务管理器换成一个计数的桩 —— 「开没开事务」由 Spring 决定, 不由测试决定。
        ProxyFactory pf = new ProxyFactory(target);
        pf.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        BillingEngineService proxy = (BillingEngineService) pf.getProxy();

        // 显式 stub 两条「让 estimate 走 SOURCE_NONE」的依赖 —— 不靠 Mockito 的默认返回值:
        // ① findActive 默认返 Optional.empty(); ② selectOne 默认返 null。
        // 若哪天有人给 findActive 加了共享 stub (返 present), estimate 会走到求值分支,
        // 抛出的异常又被 estimateAndAccrue 的 catch (Exception) 吞掉 → 转成失败取证 →
        // 内层 REQUIRES_NEW 再开一个事务 → 计数变成 2, 失败信息只剩「expected 1 but was 2」,
        // 根本看不出真因。显式写出来, 意图就在代码里。
        when(overrideService.findActive(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(ruleMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        // stub 之上 → estimate 走 SOURCE_NONE 分支 → estimateAndAccrue 在调 accrue 之前
        // 就 return 了。整条调用链里因此只有 estimateAndAccrue 自己那一次 getTransaction
        // (accrue 内部那条 REQUIRES_NEW 走不到), 计数是确定的。
        proxy.estimateAndAccrue(newDto(Map.of("weight", new BigDecimal("1.0"))));

        assertThat(tx.begins)
            .as("调用方没有事务时, estimateAndAccrue 必须自己开一个事务 (REQUIRED) —— "
              + "这是「事务边界护栏」的实际含义, 不是注解摆在那里好看"
              + " (实测开了 %s 个事务, 传播语义 %s —— 0=REQUIRED)", tx.begins, tx.propagations)
            .isEqualTo(1);
    }

    // ============================ helpers ============================

    /**
     * 计数用的 {@link PlatformTransactionManager} —— 只记「开了几次事务」+「每次的传播语义」,
     * 不做真实的事务管理 (单测里没有 DataSource)。计数点就是 {@code getTransaction} ——
     * Spring 每要开一个事务必过这一关。
     *
     * <p>顺带记下传播语义: 万一有人把注解改成 {@code REQUIRES_NEW}, 计数仍然是 1 (照样开事务),
     * 只有传播语义这一栏能一眼看出「开的是哪种事务」。
     */
    private static final class CountingTx implements PlatformTransactionManager {
        int begins = 0;
        /** 每次 getTransaction 看到的传播语义 (0 = PROPAGATION_REQUIRED)。 */
        final List<Integer> propagations = new ArrayList<>();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            begins++;
            propagations.add(definition == null ? null : definition.getPropagationBehavior());
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // no-op —— 单测不关心提交
        }

        @Override
        public void rollback(TransactionStatus status) {
            // no-op —— 单测不关心回滚
        }
    }

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

    private BillingAccrual accrual(long id, String status) {
        BillingAccrual a = new BillingAccrual();
        a.setId(id);
        a.setTenantId(TENANT);
        a.setSourceEventId(SRC);
        a.setBusinessType(BIZ);
        a.setStatus(status);
        return a;
    }
}
