package com.lumen.wms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 库存调整单 reject 请求体 (PR5-T5.2).
 *
 * <p>{@code reason} 必填，最长 255 字符；写入单据 {@code reject_reason} 列.
 */
@Data
public class RejectAdjustRequest {

    @NotBlank
    @Size(max = 255)
    private String reason;
}
