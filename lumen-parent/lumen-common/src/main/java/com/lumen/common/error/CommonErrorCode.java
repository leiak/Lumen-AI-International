package com.lumen.common.error;

import lombok.Getter;

@Getter
public enum CommonErrorCode implements ErrorCode {
    SUCCESS(0, "success", 200),
    BAD_REQUEST(400, "请求参数错误", 400),
    UNAUTHORIZED(401, "未认证", 401),
    FORBIDDEN(403, "无权限", 403),
    NOT_FOUND(404, "资源不存在", 404),
    PARAM_INVALID(4001, "参数校验失败", 400),
    INTERNAL_ERROR(500, "服务器内部错误", 500);

    private final int code;
    private final String message;
    private final int httpStatus;

    CommonErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}