package com.lumen.dispatch.dto;

import com.lumen.dispatch.entity.WmsDeliveryOrder;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 配送单 DTO 聚合类.
 *
 * <p>与 {@link com.lumen.dispatch.entity.WmsDeliveryOrder} 字段对齐,
 * 按 spec §7.1 暴露 11 类传输对象：CRUD + 8 个状态机迁移.
 */
public final class DeliveryOrderDtos {
    private DeliveryOrderDtos() {}

    // ----------------------------------------------------------------
    // Create / Update / Query
    // ----------------------------------------------------------------

    @Data
    public static class CreateDeliveryOrderRequest {
        /** 配送单号（业务号；不传由服务侧按 tenant+date 生成） */
        @Size(max = 32)
        private String no;

        /** 来源单号 (e.g. outbound_no) */
        @NotBlank
        @Size(max = 64)
        private String sourceNo;

        /** 来源类型，默认 OUTBOUND */
        @Size(max = 32)
        private String sourceType;

        /** 起点仓库 */
        @NotNull
        private Long warehouseId;

        /** 计划出发时间 */
        private LocalDateTime plannedDepartAt;

        /** 总重 / 总体积 / 站点数 */
        @Positive
        private BigDecimal totalWeightKg;

        @Positive
        private BigDecimal totalVolumeM3;

        @Positive
        private Integer stopCount;

        /** 是否需签收（默认 1） */
        private Integer signRequired;

        /** 备注 */
        @Size(max = 500)
        private String remark;
    }

    @Data
    public static class UpdateDeliveryOrderRequest {
        private LocalDateTime plannedDepartAt;
        private BigDecimal totalWeightKg;
        private BigDecimal totalVolumeM3;
        private Integer stopCount;
        private Integer signRequired;
        private String remark;
    }

    @Data
    public static class QueryDeliveryOrderRequest {
        private String status;
        private Long vehicleId;
        private Long driverId;
        private Long warehouseId;
    }

    // ----------------------------------------------------------------
    // VO
    // ----------------------------------------------------------------

    @Data
    public static class DeliveryOrderVO {
        private Long id;
        private Long tenantId;
        private String sourceNo;
        private String sourceType;
        private String no;
        private String status;
        private Long warehouseId;
        private Long vehicleId;
        private Long driverId;
        private Long carrierId;
        private LocalDateTime plannedDepartAt;
        private LocalDateTime actualDepartAt;
        private LocalDateTime arrivedAt;
        private LocalDateTime deliveredAt;
        private BigDecimal totalWeightKg;
        private BigDecimal totalVolumeM3;
        private Integer stopCount;
        private Integer signRequired;
        private String signPhotoUrl;
        private Long exceptionId;
        private String remark;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public static DeliveryOrderVO from(WmsDeliveryOrder o) {
            if (o == null) return null;
            DeliveryOrderVO vo = new DeliveryOrderVO();
            vo.setId(o.getId());
            vo.setTenantId(o.getTenantId());
            vo.setSourceNo(o.getSourceNo());
            vo.setSourceType(o.getSourceType());
            vo.setNo(o.getNo());
            vo.setStatus(o.getStatus());
            vo.setWarehouseId(o.getWarehouseId());
            vo.setVehicleId(o.getVehicleId());
            vo.setDriverId(o.getDriverId());
            vo.setCarrierId(o.getCarrierId());
            vo.setPlannedDepartAt(o.getPlannedDepartAt());
            vo.setActualDepartAt(o.getActualDepartAt());
            vo.setArrivedAt(o.getArrivedAt());
            vo.setDeliveredAt(o.getDeliveredAt());
            vo.setTotalWeightKg(o.getTotalWeightKg());
            vo.setTotalVolumeM3(o.getTotalVolumeM3());
            vo.setStopCount(o.getStopCount());
            vo.setSignRequired(o.getSignRequired());
            vo.setSignPhotoUrl(o.getSignPhotoUrl());
            vo.setExceptionId(o.getExceptionId());
            vo.setRemark(o.getRemark());
            vo.setCreatedAt(o.getCreatedAt());
            vo.setUpdatedAt(o.getUpdatedAt());
            return vo;
        }
    }

    // ----------------------------------------------------------------
    // State machine requests
    // ----------------------------------------------------------------

    /** assign — PENDING_DISPATCH → DISPATCHED */
    @Data
    public static class AssignRequest {
        @NotNull
        private Long vehicleId;
        @NotNull
        private Long driverId;
    }

    /** load — DISPATCHED → LOADED */
    @Data
    public static class LoadRequest {
        private LocalDateTime loadedAt;
    }

    /** depart — LOADED → ENROUTE */
    @Data
    public static class DepartRequest {
        private LocalDateTime departedAt;
    }

    /** arrive — ENROUTE → ARRIVED + stop.arrived_at */
    @Data
    public static class ArriveRequest {
        @NotNull
        private Long stopId;
        private LocalDateTime arrivedAt;
    }

    /** sign — ARRIVED → SIGNED + stop.completed_at */
    @Data
    public static class SignRequest {
        @NotNull
        private Long stopId;
        @NotBlank
        private String signPhotoUrl;
    }

    /** cancel — any non-terminal → CANCELLED */
    @Data
    public static class CancelRequest {
        @NotBlank
        @Size(max = 500)
        private String reason;
    }

    /** raiseException — non-terminal → EXCEPTION + open exception work order */
    @Data
    public static class RaiseExceptionRequest {
        @NotBlank
        @Size(max = 32)
        private String type;            // REJECTED/OVERTIME/DAMAGE/ADDR_ERR
        @NotBlank
        @Size(max = 1000)
        private String description;
        private Long stopId;            // optional
    }
}
