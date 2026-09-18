package com.lumen.numbering.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_number_sequence")
public class SysNumberSequence {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String ruleCode;
    private String period;
    private Long currentValue;
    @Version
    private Integer version;
    private LocalDateTime updatedAt;
}
