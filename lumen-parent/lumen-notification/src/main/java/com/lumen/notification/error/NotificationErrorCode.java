package com.lumen.notification.error;

import com.lumen.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum NotificationErrorCode implements ErrorCode {
    TEMPLATE_NOT_FOUND(13001, "通知模板不存在", 404),
    CHANNEL_PROVIDER_MISSING(13002, "渠道提供者未配置", 500);

    private final int code;
    private final String message;
    private final int httpStatus;
}
