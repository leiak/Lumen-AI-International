package com.lumen.customer.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lumen.common.api.R;
import com.lumen.common.dto.TrackQuery;
import com.lumen.common.tenant.TenantContext;
import com.lumen.customer.dto.PortalPodResponse;
import com.lumen.customer.dto.PortalTrackDetail;
import com.lumen.customer.dto.PortalTrackEvent;
import com.lumen.customer.dto.PortalTrackListItem;
import com.lumen.customer.security.CustomerContext;
import com.lumen.dispatch.service.DeliveryOrderService;
import com.lumen.wms.service.PodService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 客户门户 — 运单追踪端点 (Iteration 4.5A.2).
 *
 * <p>路由前缀 {@code /api/customer/track} — 由 JwtAuthenticationFilter 强制要求 {@code aud=portal}.
 * 由 {@code CustomerContextFilter} 把 active customerId 注入 ThreadLocal, 此处用 {@code requireCustomerId()} 取出.
 */
@RestController
@RequestMapping("/api/customer/track")
public class PortalTrackController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PortalTrackController.class);

    private final DeliveryOrderService deliveryOrderService;
    private final PodService podService;

    public PortalTrackController(DeliveryOrderService deliveryOrderService, PodService podService) {
        this.deliveryOrderService = deliveryOrderService;
        this.podService = podService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('customer:track:view')")
    public R<Map<String, Object>> list(
        @RequestParam(required = false) String doNo,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size) {
        Long customerId = CustomerContext.requireCustomerId();
        TrackQuery q = new TrackQuery(doNo, status, page, size);
        IPage<?> p = deliveryOrderService.pageForCustomer(TenantContext.get(), customerId, q);
        List<PortalTrackListItem> items = p.getRecords().stream()
            .map(PortalTrackController::toTrackListItem)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        return R.ok(Map.of("records", items, "total", p.getTotal()));
    }

    @GetMapping("/{doId}")
    @PreAuthorize("hasAuthority('customer:track:view')")
    public R<PortalTrackDetail> detail(@PathVariable Long doId) {
        Long customerId = CustomerContext.requireCustomerId();
        Map<String, Object> raw = deliveryOrderService.getDetailForCustomer(
            TenantContext.get(), customerId, doId);
        return R.ok(toTrackDetail(raw));
    }

    @GetMapping("/{doId}/pod")
    @PreAuthorize("hasAuthority('customer:track:view')")
    public R<PortalPodResponse> pod(@PathVariable Long doId) {
        Long customerId = CustomerContext.requireCustomerId();
        // 4.5A.3 fix: ownership guard before pod fetch (T3.2 spec review C1 — cross-tenant/cross-customer leak)
        // Reuse getDetailForCustomer's cross-customer/cross-tenant 404 (throws DeliveryOrderNotFoundException).
        deliveryOrderService.getDetailForCustomer(TenantContext.get(), customerId, doId);
        Map<String, Object> raw = podService.getForCustomer(
            TenantContext.get(), customerId, doId);
        return R.ok(toPodResponse(raw));
    }

    // --- private DTO converters ---

    private static PortalTrackListItem toTrackListItem(Object record) {
        if (!(record instanceof Map)) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) record;
        return new PortalTrackListItem(
            asLong(m.get("doId")),
            asString(m.get("doNo")),
            asString(m.get("status")),
            asString(m.get("originAddress")),
            asString(m.get("destAddress")),
            asString(m.get("recipientName")),
            asString(m.get("recipientPhone")),
            asLocalDateTime(m.get("createdAt")),
            asLocalDateTime(m.get("signedAt")));
    }

    private static PortalTrackDetail toTrackDetail(Map<String, Object> m) {
        if (m == null) return null;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> timelineRaw = (List<Map<String, Object>>) m.getOrDefault("timeline", List.of());
        List<PortalTrackEvent> timeline = timelineRaw.stream()
            .map(PortalTrackController::toTrackEvent)
            .collect(Collectors.toList());
        return new PortalTrackDetail(
            asLong(m.get("doId")),
            asString(m.get("doNo")),
            asString(m.get("status")),
            asString(m.get("originAddress")),
            asString(m.get("destAddress")),
            asString(m.get("recipientName")),
            asString(m.get("recipientPhone")),
            asBigDecimal(m.get("weight")),
            asBigDecimal(m.get("volume")),
            asLocalDateTime(m.get("createdAt")),
            asLocalDateTime(m.get("signedAt")),
            timeline);
    }

    private static PortalTrackEvent toTrackEvent(Map<String, Object> m) {
        return new PortalTrackEvent(
            asString(m.get("status")),
            asString(m.get("eventName")),
            asString(m.get("operator")),
            asLocalDateTime(m.get("occurredAt")),
            asString(m.get("remark")));
    }

    private static PortalPodResponse toPodResponse(Map<String, Object> m) {
        if (m == null) return null;
        return new PortalPodResponse(
            asLong(m.get("doId")),
            asString(m.get("podType")),
            asString(m.get("imageUrl")),
            asString(m.get("signatureBase64")),
            asString(m.get("signerName")),
            asString(m.get("signedAt")));
    }

    private static Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); }
        catch (NumberFormatException e) { log.warn("could not parse Long: {}", o); return null; }
    }

    private static String asString(Object o) { return o == null ? null : o.toString(); }

    private static BigDecimal asBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        try { return new BigDecimal(o.toString()); }
        catch (NumberFormatException e) { log.warn("could not parse BigDecimal: {}", o); return null; }
    }

    private static LocalDateTime asLocalDateTime(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDateTime ldt) return ldt;
        if (o instanceof java.time.Instant inst) return inst.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        if (o instanceof java.util.Date d) return d.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        try { return LocalDateTime.parse(o.toString()); }
        catch (java.time.format.DateTimeParseException e) { log.warn("could not parse LocalDateTime: {}", o); return null; }
    }
}