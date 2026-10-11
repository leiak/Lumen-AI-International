package com.lumen.wms.approval;

import com.lumen.wms.WmsConstants;
import com.lumen.wms.entity.WmsThresholdConfig;
import com.lumen.wms.service.ThresholdConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 阈值评估器单元测试 (Iteration 3.0a PR3-T3.3).
 *
 * <p>使用纯 Mockito (无 Spring 上下文) — {@code ThresholdEvaluator} 是一个
 * 无 IO 的纯函数组件,只需把 {@code ThresholdConfigService} 桩起来即可验证
 * 各分支.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ThresholdEvaluator 单元测试")
class ThresholdEvaluatorTest {

    @Mock
    private ThresholdConfigService cfgSvc;

    @InjectMocks
    private ThresholdEvaluator evaluator;

    private WmsThresholdConfig cfg;

    @BeforeEach
    void setUp() {
        cfg = new WmsThresholdConfig();
        cfg.setInbAmountThreshold(new BigDecimal("1000"));
        cfg.setInbQtyThreshold(new BigDecimal("50"));
        cfg.setOutAmountThreshold(new BigDecimal("2000"));
        cfg.setOutQtyThreshold(new BigDecimal("100"));
        cfg.setAdjAmountThreshold(new BigDecimal("500"));
        cfg.setAdjQtyThreshold(new BigDecimal("20"));
        cfg.setApprovalRequiredWhenExceed(1);
        when(cfgSvc.getOrInit()).thenReturn(cfg);
    }

    @Test
    @DisplayName("approvalRequiredWhenExceed=0 时直接关闭审批闸门")
    void disabled_when_approval_required_when_exceed_is_0() {
        cfg.setApprovalRequiredWhenExceed(0);
        boolean result = evaluator.requiresApproval(
                WmsConstants.BIZ_INBOUND,
                new BigDecimal("999999"),
                new BigDecimal("999999"));
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("approvalRequiredWhenExceed=null 时直接关闭审批闸门")
    void disabled_when_approval_required_when_exceed_is_null() {
        cfg.setApprovalRequiredWhenExceed(null);
        boolean result = evaluator.requiresApproval(
                WmsConstants.BIZ_INBOUND,
                new BigDecimal("999999"),
                new BigDecimal("999999"));
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("金额超过阈值时返回 true (入库)")
    void requires_approval_when_amount_exceeds_threshold() {
        boolean result = evaluator.requiresApproval(
                WmsConstants.BIZ_INBOUND,
                new BigDecimal("1500"),
                new BigDecimal("10"));
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("数量超过阈值时返回 true (出库)")
    void requires_approval_when_qty_exceeds_threshold() {
        boolean result = evaluator.requiresApproval(
                WmsConstants.BIZ_OUTBOUND,
                new BigDecimal("10"),
                new BigDecimal("150"));
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("金额阈值为 0 时即便金额很大也不触发审批 (维度关闭)")
    void disabled_when_threshold_zero_and_amount_positive() {
        cfg.setInbAmountThreshold(BigDecimal.ZERO);
        boolean result = evaluator.requiresApproval(
                WmsConstants.BIZ_INBOUND,
                new BigDecimal("999999"),
                new BigDecimal("10"));
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("金额/数量双阈值都为 0 时不触发审批")
    void disabled_when_both_thresholds_zero() {
        cfg.setInbAmountThreshold(BigDecimal.ZERO);
        cfg.setInbQtyThreshold(BigDecimal.ZERO);
        boolean result = evaluator.requiresApproval(
                WmsConstants.BIZ_INBOUND,
                new BigDecimal("999999"),
                new BigDecimal("999999"));
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("未知的 bizKind 不抛异常,返回 false (默认 0 阈值)")
    void disabled_when_unknown_bizKind() {
        boolean result = evaluator.requiresApproval(
                "WMS_UNKNOWN_KIND",
                new BigDecimal("999999"),
                new BigDecimal("999999"));
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("调整单据按 adj 阈值评估 (负向用例)")
    void requires_approval_adjust_kind_uses_adj_thresholds() {
        boolean result = evaluator.requiresApproval(
                WmsConstants.BIZ_ADJUST,
                new BigDecimal("1000"),  // > adjAmountThreshold 500
                new BigDecimal("1"));
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("金额与数量均未超过阈值时返回 false")
    void disabled_when_neither_exceeds() {
        boolean result = evaluator.requiresApproval(
                WmsConstants.BIZ_INBOUND,
                new BigDecimal("500"),
                new BigDecimal("20"));
        assertThat(result).isFalse();
    }
}
