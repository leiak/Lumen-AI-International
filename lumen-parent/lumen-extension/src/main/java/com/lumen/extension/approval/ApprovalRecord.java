package com.lumen.extension.approval;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_approval_record")
public class ApprovalRecord extends BaseEntity {
    private String bizType;
    private String bizId;
    // tenantId inherited from BaseEntity
    private Integer level;
    private String status;          // ApprovalStatus.name()
    private Long applicantId;
    private Long approverId;
    private String approvalRole;
    private String payload;         // JSON 字符串
    private String comment;
    private LocalDateTime decidedAt;
}
