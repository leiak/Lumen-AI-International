package com.lumen.extension.approval;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubmitApprovalRequest {
    private String bizType;
    private String bizId;
    private Long applicantId;
    private Map<String, Object> payload;
    private String comment;
}
