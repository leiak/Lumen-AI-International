package com.lumen.customer.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lumen.common.api.R;
import com.lumen.common.dto.TrackQuery;
import com.lumen.common.tenant.TenantContext;
import com.lumen.customer.dto.PortalCancelOrderRequest;
import com.lumen.customer.dto.PortalCreateOrderRequest;
import com.lumen.customer.error.PortalErrorCode;
import com.lumen.customer.exception.CustomerException;
import com.lumen.customer.security.CustomerContext;
import com.lumen.dispatch.dto.PortalOrderRequest;
import com.lumen.dispatch.entity.WmsDeliveryOrder;
import com.lumen.dispatch.exception.DeliveryOrderNotFoundException;
import com.lumen.dispatch.exception.PortalBadRequestException;
import com.lumen.dispatch.service.DeliveryOrderService;
import com.lumen.finance.dto.EstimateResult;
import com.lumen.finance.service.BillingEngineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户门户订单 controller (Iteration 4.5B.2 T2.6).
 *
 * <p>5 个端点 (路由前缀 {@code /api/customer/order}, 与 {@link PortalTrackController}
 * 一致 — 由 {@code JwtAuthenticationFilter} 强制要求 {@code aud=portal};
 * {@code t_sys_perm.path} 列里登记为 FE-facing {@code /portal/order}:
 * <ol>
 *   <li>POST {@code /preview}      — {@code customer:order:create} — BillingEngineService.estimate</li>
 *   <li>POST {@code /}             — {@code customer:order:create} — DeliveryOrderService.createForCustomer</li>
 *   <li>GET  {@code /}             — {@code customer:order:view}   — DeliveryOrderService.pageForCustomer</li>
 *   <li>GET  {@code /{doId}}       — {@code customer:order:view}   — DeliveryOrderService.getDetailForCustomer</li>
 *   <li>POST {@code /{doId}/cancel} — {@code customer:order:cancel} — DeliveryOrderService.cancelForCustomer</li>
 * </ol>
 *
 * <p>关键契约:
 * <ul>
 *   <li>tenantId / customerId 从 {@link CustomerContext} 取, 强制注入到 service 调用 —
 *       不读 DTO, 防越权 (与 {@link PortalTrackController} 同).</li>
 *   <li>dispatch 抛 {@link PortalBadRequestException} → controller 重新包装为
 *       {@link CustomerException} (用 {@link PortalErrorCode} 的 portal-side code),
 *       由 {@code CustomerExceptionHandler} 翻译成 HTTP 响应.</li>
 *   <li>dispatch 抛 {@link DeliveryOrderNotFoundException} → controller 翻译为
 *       {@code CustomerException(404_001)} (uniform 404, 不暴露存在性).</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/customer/order")
@RequiredArgsConstructor
public class PortalOrderController {

    private final DeliveryOrderService deliveryOrderService;
    private final BillingEngineService billingEngineService;

    // ================================================================
    //  1. POST /preview — 估价预览 (不创建订单)
    // ================================================================

    /**
     * {@code POST /api/customer/order/preview} — 估价预览.
     *
     * <p>不创建订单, 只调 {@code BillingEngineService.estimate} 拿 {@link EstimateResult}.
     * <p>注: preview 不做 pickup window 校验 — 即使窗口无效也返回估算值 (前端表单草稿
     * 阶段也算价). pickup window 校验在 createForCustomer (T2.3 spec).
     *
     * @return JSON body — amount/source/ruleId/overrideId/currency/breakdown
     */
    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('customer:order:create')")
    public R<Map<String, Object>> preview(@Valid @RequestBody PortalCreateOrderRequest req) {
        Long tenantId = requireTenantId();
        Long customerId = requireCustomerId();
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("weight", req.totalWeightKg());
        env.put("stopCount", req.stopCount());
        env.put("distance", BigDecimal.ZERO);
        env.put("totalWeightKg", req.totalWeightKg());
        EstimateResult result = billingEngineService.estimate(tenantId, customerId, "LAST_MILE", env);
        return R.ok(estimateResultToMap(result));
    }

    // ================================================================
    //  2. POST / — 创建订单
    // ================================================================

    /**
     * {@code POST /api/customer/order} — 创建订单.
     *
     * <p>调 {@code DeliveryOrderService.createForCustomer} (T2.3):
     * <ul>
     *   <li>校验 pickup window (§7.2: start >= now+2h, <= now+30d, end > start, end <= start+8h, 同日)</li>
     *   <li>建 DO (status=PENDING_DISPATCH, sourceType=PORTAL, no=PO- + 8 hex)</li>
     *   <li>同步 {@code estimateAndAccrue}, 失败不阻塞 (后台 job 重试)</li>
     *   <li>发 {@code DeliveryOrderStateChangedEvent} (from=null, action=create)</li>
     * </ul>
     *
     * @throws CustomerException(400_001/400_002) pickup window 非法
     */
    @PostMapping
    @PreAuthorize("hasAuthority('customer:order:create')")
    public R<Map<String, Object>> create(@Valid @RequestBody PortalCreateOrderRequest req) {
        Long tenantId = requireTenantId();
        Long customerId = requireCustomerId();
        try {
            PortalOrderRequest dispatchReq = new PortalOrderRequest(
                req.warehouseId(),
                req.shipperAddress(),
                req.consigneeAddress(),
                req.totalWeightKg(),
                req.totalVolumeM3(),
                req.stopCount(),
                req.plannedPickupWindowStart(),
                req.plannedPickupWindowEnd(),
                req.remark()
            );
            WmsDeliveryOrder order = deliveryOrderService.createForCustomer(
                tenantId, customerId, dispatchReq);
            return R.ok(orderToMap(order));
        } catch (PortalBadRequestException ex) {
            throw mapPortalBadRequest(ex);
        }
    }

    // ================================================================
    //  3. GET / — 订单列表
    // ================================================================

    /**
     * {@code GET /api/customer/order} — 当前客户订单列表 (分页).
     *
     * <p>参数: {@code doNo?}, {@code status?}, {@code page=1}, {@code size=20}.
     * 与 {@link PortalTrackController#list} 一致, 共享 {@link TrackQuery} (放 lumen-common
     * 避免 dispatch → customer 循环).
     *
     * @return records + total + page + size
     */
    @GetMapping
    @PreAuthorize("hasAuthority('customer:order:view')")
    public R<Map<String, Object>> list(
        @RequestParam(required = false) String doNo,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size) {
        Long tenantId = requireTenantId();
        Long customerId = requireCustomerId();
        TrackQuery q = new TrackQuery(doNo, status, page, size);
        IPage<Map<String, Object>> p = deliveryOrderService.pageForCustomer(tenantId, customerId, q);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", p.getRecords());
        data.put("total", p.getTotal());
        data.put("page", p.getCurrent());
        data.put("size", p.getSize());
        return R.ok(data);
    }

    // ================================================================
    //  4. GET /{doId} — 订单详情
    // ================================================================

    /**
     * {@code GET /api/customer/order/{doId}} — 单订单详情.
     *
     * <p>{@code DeliveryOrderService.getDetailForCustomer} 内部做
     * tenant + customer ownership 校验, 跨租户 / 跨客户 / 不存在 都抛
     * {@link DeliveryOrderNotFoundException} (uniform 404, 防越权 + 防存在性泄漏).
     *
     * @throws CustomerException(404_001) doId 不存在或无权访问
     */
    @GetMapping("/{doId}")
    @PreAuthorize("hasAuthority('customer:order:view')")
    public R<Map<String, Object>> detail(@PathVariable Long doId) {
        Long tenantId = requireTenantId();
        Long customerId = requireCustomerId();
        try {
            Map<String, Object> raw = deliveryOrderService.getDetailForCustomer(tenantId, customerId, doId);
            return R.ok(raw);
        } catch (DeliveryOrderNotFoundException ex) {
            // 统一按 404 处理 — 不暴露存在性 (跨租户/跨客户/不存在 都返同一个错)
            throw new CustomerException(404_001, "订单不存在或无权访问");
        }
    }

    // ================================================================
    //  5. POST /{doId}/cancel — 取消订单
    // ================================================================

    /**
     * {@code POST /api/customer/order/{doId}/cancel} — 取消订单.
     *
     * <p>调 {@code DeliveryOrderService.cancelForCustomer} (T2.4):
     * <ul>
     *   <li>uniform 404 (跨租户 / 跨客户 / 不存在)</li>
     *   <li>仅 PENDING_DISPATCH 可取消; 其它状态抛 {@code PortalBadRequestException} (409_001)</li>
     *   <li>改 status + remark 后缀, 发 {@code DeliveryOrderStateChangedEvent} (action=cancel)</li>
     * </ul>
     *
     * @throws CustomerException(404_001) doId 不存在或无权访问
     * @throws CustomerException(409_001) 当前状态不是 PENDING_DISPATCH
     */
    @PostMapping("/{doId}/cancel")
    @PreAuthorize("hasAuthority('customer:order:cancel')")
    public R<Map<String, Object>> cancel(@PathVariable Long doId,
                                         @Valid @RequestBody PortalCancelOrderRequest req) {
        Long tenantId = requireTenantId();
        Long customerId = requireCustomerId();
        try {
            WmsDeliveryOrder cancelled = deliveryOrderService.cancelForCustomer(
                tenantId, customerId, doId, req.reason());
            return R.ok(orderToMap(cancelled));
        } catch (DeliveryOrderNotFoundException ex) {
            throw new CustomerException(404_001, "订单不存在或无权访问");
        } catch (PortalBadRequestException ex) {
            throw mapPortalBadRequest(ex);
        }
    }

    // ================================================================
    //  private helpers
    // ================================================================

    /**
     * 取 tenantId (强制注入 — 不可为 null).
     * <p>{@link TenantContext} 由 {@code JwtAuthenticationFilter} 从 JWT {@code tid} claim
     * 写入; 入 {@code /api/customer/**} 路径时 portal filter 已 set, 必非 null.
     * <p>本方法再 set 一次是 idempotent 兜底 — 万一上游 filter 调用顺序变化
     * 也不会读出 null (这与 {@link PortalTrackController} 用 {@code TenantContext.get()}
     * 直接取同源, 但多一道校验便于 fail-fast 排查).
     */
    private Long requireTenantId() {
        Long t = TenantContext.get();
        if (t == null) {
            throw new CustomerException(401_001, "未识别租户 (JWT 缺失 tenantId)");
        }
        TenantContext.set(t);  // idempotent — MyBatis Plus 多租户拦截器读这里
        return t;
    }

    /**
     * 取 customerId (强制注入 — 不可为 null).
     * <p>{@link CustomerContext#requireCustomerId()} 缺身份时抛 401_002 (由
     * {@code CustomerExceptionHandler} 翻译成 401).
     */
    private Long requireCustomerId() {
        return CustomerContext.requireCustomerId();
    }

    /**
     * WmsDeliveryOrder → Map (serialization-friendly).
     * <p>把 entity 字段平铺到 Map, 避免 entity 直接进 controller 层 JSON
     * (lombok @Data 不暴露 'deleted'/'version' 等敏感字段).
     */
    private Map<String, Object> orderToMap(WmsDeliveryOrder order) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("doId", order.getId());
        m.put("doNo", order.getNo());
        m.put("status", order.getStatus());
        m.put("sourceType", order.getSourceType());
        m.put("warehouseId", order.getWarehouseId());
        m.put("customerId", order.getCustomerId());
        m.put("plannedPickupWindowStart", order.getPlannedPickupWindowStart());
        m.put("plannedPickupWindowEnd", order.getPlannedPickupWindowEnd());
        m.put("totalWeightKg", order.getTotalWeightKg());
        m.put("totalVolumeM3", order.getTotalVolumeM3());
        m.put("stopCount", order.getStopCount());
        m.put("remark", order.getRemark());
        m.put("createdAt", order.getCreatedAt());
        return m;
    }

    /**
     * EstimateResult → Map (serialization-friendly).
     */
    private Map<String, Object> estimateResultToMap(EstimateResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amount", r.amount());
        m.put("source", r.source());
        m.put("ruleId", r.ruleId());
        m.put("overrideId", r.overrideId());
        m.put("currency", r.currency());
        m.put("breakdown", r.breakdown());
        return m;
    }

    /**
     * PortalBadRequestException → CustomerException 重映射.
     *
     * <p>dispatch 不依赖 lumen-customer, 故 dispatch-side 的
     * {@link PortalBadRequestException#getCode()} 是 int 数值, 不 import
     * {@link PortalErrorCode}. 这里做 code → PortalErrorCode 的翻译, 由
     * {@code CustomerExceptionHandler} 走 HTTP 状态映射.
     *
     * <p>约定 (PortalErrorCode 注释):
     * <ul>
     *   <li>400_001 — pickup window 终点非法 (跨日 / end <= start / end > start+8h)</li>
     *   <li>400_002 — pickup window 起点非法 (太近 / 太远)</li>
     *   <li>409_001 — 不能取消的状态</li>
     * </ul>
     */
    private CustomerException mapPortalBadRequest(PortalBadRequestException ex) {
        int code = ex.getCode();
        PortalErrorCode pec = switch (code) {
            case 400_001 -> PortalErrorCode.ORDER_PICKUP_WINDOW_INVALID;
            case 400_002 -> PortalErrorCode.ORDER_PICKUP_WINDOW_TOO_SHORT;
            case 409_001 -> PortalErrorCode.ORDER_NOT_CANCELLABLE;
            default -> PortalErrorCode.ORDER_PICKUP_WINDOW_INVALID;  // 兜底 (未知 portal-side code)
        };
        return new CustomerException(pec.getCode(), ex.getMessage());
    }
}
