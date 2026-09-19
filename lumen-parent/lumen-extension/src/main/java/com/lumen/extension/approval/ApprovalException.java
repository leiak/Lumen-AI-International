package com.lumen.extension.approval;

import com.lumen.common.error.BizException;
import com.lumen.common.error.ErrorCode;
import lombok.Getter;

@Getter
public class ApprovalException extends BizException {
    public ApprovalException(int code, String message, int httpStatus) {
        super(new ErrorCode() {
            @Override public int getCode() { return code; }
            @Override public String getMessage() { return message; }
            @Override public int getHttpStatus() { return httpStatus; }
        });
    }

    public static ApprovalException unknownBizType(String bizType) {
        return new ApprovalException(15001, "Unknown bizType: " + bizType, 400);
    }

    public static ApprovalException notPending() {
        return new ApprovalException(15002, "审批已结束", 400);
    }

    public static ApprovalException noPermission() {
        return new ApprovalException(15003, "无审批权限", 403);
    }

    public static ApprovalException notApplicant() {
        return new ApprovalException(15004, "只有申请人能撤回", 403);
    }
}
