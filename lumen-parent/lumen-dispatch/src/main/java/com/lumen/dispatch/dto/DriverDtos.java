package com.lumen.dispatch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 司机主数据 DTO 聚合类.
 *
 * <p>字段对齐 {@link com.lumen.dispatch.entity.WmsDriver}.
 */
public final class DriverDtos {
    private DriverDtos() {}

    @Data
    public static class CreateDriverRequest {
        @NotBlank
        @Size(max = 64)
        private String name;

        @NotBlank
        @Size(max = 32)
        private String phone;

        @Size(max = 64)
        private String licenseNo;

        @Size(max = 32)
        private String status;

        @Size(max = 256)
        private String remark;
    }

    @Data
    public static class UpdateDriverRequest {
        @Size(max = 64)
        private String name;

        @Size(max = 32)
        private String phone;

        @Size(max = 64)
        private String licenseNo;

        @Size(max = 32)
        private String status;

        @Size(max = 256)
        private String remark;
    }

    @Data
    public static class QueryDriverRequest {
        private String name;
        private String status;
    }

    @Data
    public static class DriverVO {
        private Long id;
        private Long tenantId;
        private String name;
        private String phone;
        private String licenseNo;
        private String status;
        private String remark;
    }
}