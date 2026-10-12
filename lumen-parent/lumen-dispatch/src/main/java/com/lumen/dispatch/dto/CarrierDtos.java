package com.lumen.dispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 承运人主数据 DTO 聚合类.
 *
 * <p>字段对齐 {@link com.lumen.dispatch.entity.WmsCarrier}.
 */
public final class CarrierDtos {
    private CarrierDtos() {}

    @Data
    public static class CreateCarrierRequest {
        @NotBlank
        @Size(max = 32)
        private String code;

        @NotBlank
        @Size(max = 128)
        private String name;

        @Size(max = 64)
        private String contact;

        @Size(max = 32)
        private String status;
    }

    @Data
    public static class UpdateCarrierRequest {
        @Size(max = 32)
        private String code;

        @Size(max = 128)
        private String name;

        @Size(max = 64)
        private String contact;

        @Size(max = 32)
        private String status;
    }

    @Data
    public static class QueryCarrierRequest {
        private String code;
        private String name;
        private String status;
    }

    @Data
    public static class CarrierVO {
        private Long id;
        private Long tenantId;
        private String code;
        private String name;
        private String contact;
        private String status;
    }
}