package com.lumen.finance.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 计费规则项 - Aviator 表达式 (spec §3.2).
 */
@Data
@TableName("t_billing_rule_item")
public class BillingRuleItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ruleId;
    private Integer sequence;            // 1, 2, 3
    private String itemType;             // BASE / DISTANCE / WEIGHT / EXTRA / TAX
    private String expression;           // Aviator 表达式
    private String expressionHash;       // SHA-256
    private String paramJson;            // 默认参数 JSON
    private String resultField;          // 写入 invoice 的字段
    private String remark;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updatedAt;
    @TableLogic @TableField(select = false, fill = FieldFill.INSERT) private Integer deleted;
    @Version @TableField(fill = FieldFill.INSERT) private Integer version;
}