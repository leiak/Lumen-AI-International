package com.lumen.dispatch.dto;

import com.lumen.dispatch.entity.WmsDeliveryStop;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 配送站点 DTO 聚合类.
 *
 * <p>站点由 {@code OutboundShippedEvent} 触发自动生成，不开放 controller
 * 端写入路径，故仅暴露 {@link StopVO}.
 */
public final class DeliveryStopDtos {
    private DeliveryStopDtos() {}

    @Data
    public static class StopVO {
        private Long id;
        private Long tenantId;
        private Long orderId;
        private Integer stopNo;
        private String address;
        private String contactName;
        private String contactPhone;
        private BigDecimal latitude;
        private BigDecimal longitude;
        private LocalDateTime plannedAt;
        private LocalDateTime arrivedAt;
        private LocalDateTime completedAt;
        private String status;
        private String failureReason;

        public static StopVO from(WmsDeliveryStop s) {
            if (s == null) return null;
            StopVO vo = new StopVO();
            vo.setId(s.getId());
            vo.setTenantId(s.getTenantId());
            vo.setOrderId(s.getOrderId());
            vo.setStopNo(s.getStopNo());
            vo.setAddress(s.getAddress());
            vo.setContactName(s.getContactName());
            vo.setContactPhone(s.getContactPhone());
            vo.setLatitude(s.getLatitude());
            vo.setLongitude(s.getLongitude());
            vo.setPlannedAt(s.getPlannedAt());
            vo.setArrivedAt(s.getArrivedAt());
            vo.setCompletedAt(s.getCompletedAt());
            vo.setStatus(s.getStatus());
            vo.setFailureReason(s.getFailureReason());
            return vo;
        }
    }
}
