package com.lumen.cs.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_sla_config")
public class SlaConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String severity;
    private String level;
    private Integer responseMinutes;
    private Integer resolveMinutes;
    private String escalateToLevel;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updatedAt;
    @TableLogic @TableField(select = false, fill = FieldFill.INSERT) private Integer deleted;
    @Version @TableField(fill = FieldFill.INSERT) private Integer version;
}