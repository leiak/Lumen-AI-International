package com.lumen.finance.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 规则变更审计 (spec §3.6).
 */
@Data
@TableName("t_billing_rule_audit")
public class BillingRuleAudit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long ruleId;
    private Integer ruleVersion;
    private String changeType;           // CREATE / UPDATE / DISABLE / DELETE
    private String diffJson;
    private Long operatorId;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime changedAt;
    private String remark;
    @TableLogic @TableField(select = false, fill = FieldFill.INSERT) private Integer deleted;
}