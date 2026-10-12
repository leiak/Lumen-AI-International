package com.lumen.dispatch.dto;

import com.lumen.dispatch.entity.WmsDeliveryException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配送异常工单 DTO 聚合类.
 *
 * <p>与 {@link com.lumen.dispatch.entity.WmsDeliveryException} 字段对齐,
 * 按 spec §7.4 暴露 7 类传输对象.
 */
public final class DeliveryExceptionDtos {
    private DeliveryExceptionDtos() {}

    @Data
    public static class CreateDeliveryExceptionRequest {
        @NotNull
        private Long orderId;

        @NotBlank
        @Size(max = 32)
        private String type;

        @NotBlank
        @Size(max = 1000)
        private String description;

        @Size(max = 64)
        private String handler;
    }

    @Data
    public static class UpdateDeliveryExceptionRequest {
        @Size(max = 1000)
        private String description;
        @Size(max = 64)
        private String handler;
    }

    @Data
    public static class QueryDeliveryExceptionRequest {
        private String status;
        private String type;
        private Long orderId;
    }

    @Data
    public static class ExceptionVO {
        private Long id;
        private Long tenantId;
        private Long orderId;
        private String type;
        private String status;
        private String description;
        private String handler;
        private LocalDateTime resolvedAt;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public static ExceptionVO from(WmsDeliveryException e) {
            if (e == null) return null;
            ExceptionVO vo = new ExceptionVO();
            vo.setId(e.getId());
            vo.setTenantId(e.getTenantId());
            vo.setOrderId(e.getOrderId());
            vo.setType(e.getType());
            vo.setStatus(e.getStatus());
            vo.setDescription(e.getDescription());
            vo.setHandler(e.getHandler());
            vo.setResolvedAt(e.getResolvedAt());
            vo.setCreatedAt(e.getCreatedAt());
            vo.setUpdatedAt(e.getUpdatedAt());
            return vo;
        }
    }

    // ----------------------------------------------------------------
    // State machine requests
    // ----------------------------------------------------------------

    /** investigate — OPEN → INVESTIGATING */
    @Data
    public static class InvestigateRequest {
        @NotBlank
        @Size(max = 64)
        private String handler;
    }

    /** resolve — INVESTIGATING → RESOLVED */
    @Data
    public static class ResolveRequest {
        @NotBlank
        @Size(max = 1000)
        private String resolutionNotes;
    }

    /** reopen — RESOLVED/CLOSED → INVESTIGATING */
    @Data
    public static class ReopenRequest {
        @NotBlank
        @Size(max = 1000)
        private String reason;
    }
}
