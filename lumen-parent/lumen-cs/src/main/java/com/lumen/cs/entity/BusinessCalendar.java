package com.lumen.cs.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@TableName("t_business_calendar")
public class BusinessCalendar {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Integer weekday;
    private LocalTime startTime;
    private LocalTime endTime;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updatedAt;
    @TableLogic @TableField(select = false, fill = FieldFill.INSERT) private Integer deleted;
    @Version @TableField(fill = FieldFill.INSERT) private Integer version;
}