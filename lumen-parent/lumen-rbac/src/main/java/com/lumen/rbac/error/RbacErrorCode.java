package com.lumen.rbac.error;

import com.lumen.common.error.ErrorCode;
import lombok.Getter;

@Getter
public enum RbacErrorCode implements ErrorCode {
    USER_NOT_FOUND(10001, "用户不存在", 404),
    USER_PASSWORD_WRONG(10002, "用户名或密码错误", 401),
    USER_DISABLED(10003, "用户已禁用", 403),
    TOKEN_EXPIRED(10004, "token 已过期", 401),
    TOKEN_REVOKED(10005, "token 已撤销", 401),
    PERMISSION_DENIED(10006, "权限不足", 403),
    USERNAME_DUPLICATE(10007, "用户名已存在", 409);

    private final int code;
    private final String message;
    private final int httpStatus;
    RbacErrorCode(int code, String message, int httpStatus) { this.code=code; this.message=message; this.httpStatus=httpStatus; }
}
