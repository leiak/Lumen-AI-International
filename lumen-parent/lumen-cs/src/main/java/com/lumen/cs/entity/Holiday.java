package com.lumen.cs.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_holiday")
public class Holiday {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private LocalDate holidayDate;
    private String description;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updatedAt;
    @TableLogic @TableField(select = false) private Integer deleted;
    @Version private Integer version;
}