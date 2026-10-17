package com.lumen.finance.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * BillingRule DTO 集合 (Iteration 4.3.1, PR3-T3.1).
 *
 * <p>Java 17 records + bean validation。{@link CreateRequest} / {@link UpdateRequest}
 * 是 service 入参；{@link View} 是出参（包含 items）；{@link RuleItemView} 是 item 出参.
 *
 * <p>参照 lumen-cs 的 SlaConfigDtos 风格 (nested records + 私有构造).
 */
public final class BillingRuleDtos {
    private BillingRuleDtos() {}

    /**
     * 单条规则项的输入 (CreateRequest.items / UpdateRequest.items).
     *
     * <p>expression 是 Aviator 表达式字符串，service 层会调用
     * {@code BillingScriptEngine.normalizeHash(expr)} 计算 hash 并落库.
     */
    public record CreateRuleItem(
        @NotNull @Min(1) Integer sequence,
        @NotBlank String itemType,
        @NotBlank String expression,
        String paramJson,
        String resultField,
        String remark
    ) {}

    /**
     * 创建规则的入参.
     */
    public record CreateRequest(
        @NotBlank String businessType,
        @NotBlank String direction,
        @NotBlank String counterpartyType,
        String currency,                 // 缺省 CNY
        Integer priority,                // 缺省 100
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String remark,
        @NotNull List<CreateRuleItem> items
    ) {}

    /**
     * 更新规则的入参。所有字段可选，非 null 才覆盖；items 非 null 时走"全删全插".
     */
    public record UpdateRequest(
        Integer priority,
        LocalDate effectiveTo,
        String remark,
        List<CreateRuleItem> items
    ) {}

    /**
     * 单条规则项的视图（含 expressionHash）.
     */
    public record RuleItemView(
        Long id,
        Long ruleId,
        Integer sequence,
        String itemType,
        String expression,
        String expressionHash,
        String paramJson,
        String resultField,
        String remark
    ) {}

    /**
     * 规则视图（含 items）. controller / list / get 共用此出参.
     */
    public record View(
        Long id,
        Long tenantId,
        String businessType,
        String direction,
        String counterpartyType,
        String currency,
        Integer priority,
        String status,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String remark,
        List<RuleItemView> items
    ) {}
}
