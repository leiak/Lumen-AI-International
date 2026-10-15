package com.lumen.cs.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_sla_event")
public class SlaEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long exceptionId;
    private String eventType;
    private String currentLevel;
    private String severity;
    private LocalDateTime occurredAt;
    private String payload;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    @TableLogic @TableField(select = false) private Integer deleted;
    @Version private Integer version;
}