package com.lumen.finance.dto;

import java.math.BigDecimal;

/**
 * 报价估算结果 (4.5B.1 新增).
 * <p>source 标识金额来源 — 用于排错/审计:
 * <ul>
 *   <li>{@link #SOURCE_RULE} — 来自 t_billing_rule 匹配</li>
 *   <li>{@link #SOURCE_OVERRIDE} — 来自 t_customer_rate_override (高优先级)</li>
 *   <li>{@link #SOURCE_NONE} — 无匹配</li>
 * </ul>
 *
 * <p>overrideId 仅 SOURCE_OVERRIDE 时非空; ruleId 仅 SOURCE_RULE 时非空.
 * breakdown 是一行中文解释 (供 FE 显示), amount=null 表示不可估算.
 *
 * @param amount 估算金额 (null 表示不可估算)
 * @param source 来源 — RULE / OVERRIDE / NONE
 * @param ruleId 匹配的 billing_rule.id (SOURCE_RULE 时)
 * @param overrideId 匹配的 customer_rate_override.id (SOURCE_OVERRIDE 时)
 * @param currency 币种 CNY
 * @param breakdown 中文一行解释, 供 FE 显示 ("预估 ¥123.45 (按 1-3 件 30kg + 距离 5km)" 等)
 */
public record EstimateResult(BigDecimal amount,
                             String source,
                             Long ruleId,
                             Long overrideId,
                             String currency,
                             String breakdown) {

    public static final String SOURCE_RULE = "RULE";
    public static final String SOURCE_OVERRIDE = "OVERRIDE";
    public static final String SOURCE_NONE = "NONE";

    /**
     * 无匹配 — amount=null, source=NONE, breakdown=None 中文说明.
     */
    public static EstimateResult none() {
        return new EstimateResult(null, SOURCE_NONE, null, null, "CNY",
            "无匹配计费规则, 后续人工报价");
    }
}
