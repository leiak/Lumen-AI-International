package com.lumen.extension.state;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_state_transition")
public class StateTransition extends BaseEntity {
    private String stateMachineCode;
    private String fromState;
    private String toState;
    private String transitionName;
    private String guardExpression;
    private String description;
    private Integer sortOrder;
    // tenantId inherited from BaseEntity (auto-fill via @TableField(fill = FieldFill.INSERT))
}
