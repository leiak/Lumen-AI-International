package com.lumen.dispatch.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 车辆主数据 DTO 聚合类.
 *
 * <p>把 4 个 POJO 装在一个文件以减少树枝数；调用方按需 import 内部类
 * （{@code VehicleDtos.CreateVehicleRequest} 等）.
 *
 * <p>字段对齐 {@link com.lumen.dispatch.entity.WmsVehicle}.
 */
public final class VehicleDtos {
    private VehicleDtos() {}

    @Data
    public static class CreateVehicleRequest {
        @NotBlank
        @Size(max = 32)
        private String plateNo;

        @NotBlank
        @Size(max = 32)
        private String type;

        @NotNull
        @DecimalMin(value = "0.0", inclusive = false)
        private BigDecimal maxLoadKg;

        @NotNull
        @DecimalMin(value = "0.0", inclusive = false)
        private BigDecimal maxVolumeM3;

        @Size(max = 32)
        private String status;

        private Long driverId;

        private Long carrierId;

        @Size(max = 256)
        private String remark;
    }

    @Data
    public static class UpdateVehicleRequest {
        @Size(max = 32)
        private String plateNo;

        @Size(max = 32)
        private String type;

        @DecimalMin(value = "0.0", inclusive = false)
        private BigDecimal maxLoadKg;

        @DecimalMin(value = "0.0", inclusive = false)
        private BigDecimal maxVolumeM3;

        @Size(max = 32)
        private String status;

        private Long driverId;

        private Long carrierId;

        @Size(max = 256)
        private String remark;
    }

    @Data
    public static class QueryVehicleRequest {
        private String type;
        private String status;
        private String plateNo;

        /** Helper for IT/tests; mirrors the controller's parameter binding. */
        public static QueryVehicleRequest from(String type, String status, String plateNo) {
            QueryVehicleRequest q = new QueryVehicleRequest();
            q.setType(type);
            q.setStatus(status);
            q.setPlateNo(plateNo);
            return q;
        }
    }

    /**
     * 车辆 VO — 当前与 {@link com.lumen.dispatch.entity.WmsVehicle} 字段一致,
     * 保留 VO 类型便于将来加前端格式化字段（车牌前缀、车型名等）.
     */
    @Data
    public static class VehicleVO {
        private Long id;
        private Long tenantId;
        private String plateNo;
        private String type;
        private BigDecimal maxLoadKg;
        private BigDecimal maxVolumeM3;
        private String status;
        private Long driverId;
        private Long carrierId;
        private String remark;
    }
}