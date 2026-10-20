package com.lumen.customer.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.tenant.TenantContext;
import com.lumen.customer.dto.PortalCancelOrderRequest;
import com.lumen.customer.dto.PortalCreateOrderRequest;
import com.lumen.customer.exception.CustomerException;
import com.lumen.customer.security.CustomerContext;
import com.lumen.dispatch.dto.PortalOrderRequest;
import com.lumen.dispatch.entity.WmsDeliveryOrder;
import com.lumen.dispatch.exception.DeliveryOrderNotFoundException;
import com.lumen.dispatch.exception.PortalBadRequestException;
import com.lumen.dispatch.service.DeliveryOrderService;
import com.lumen.finance.dto.EstimateResult;
import com.lumen.finance.service.BillingEngineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 单元测试 for {@link PortalOrderController} (Iteration 4.5B.2 T2.6).
 *
 * <p>覆盖 5 端点的关键契约:
 * <ul>
 *   <li>preview / create / list / detail / cancel — 调对应 service 时把 customer/tenant
 *       注入正确;</li>
 *   <li>dispatch 抛 {@link PortalBadRequestException} → controller 转 {@link CustomerException};</li>
 *   <li>dispatch 抛 {@link DeliveryOrderNotFoundException} → controller 转
 *       {@code CustomerException(404_001)}.</li>
 * </ul>
 *
 * <p>纯 Mockito, 不依赖 Spring context. 与 {@link PortalTrackControllerTest} 同款约定.
 */
class PortalOrderControllerTest {

    private static final long USER_ID = 10L;
    private static final long ACTIVE_CUSTOMER_ID = 20L;
    private static final long ACTIVE_TENANT_ID = 1L;

    @Mock DeliveryOrderService deliveryOrderService;
    @Mock BillingEngineService billingEngineService;
    @InjectMocks PortalOrderController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        TenantContext.set(ACTIVE_TENANT_ID);
        CustomerContext.set(USER_ID, ACTIVE_CUSTOMER_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CustomerContext.clear();
    }

    // ================================================================
    //  preview
    // ================================================================

    @Test
    void preview_callsEstimateWithTenantCustomerAndEnv() {
        PortalCreateOrderRequest req = sampleCreateRequest();
        EstimateResult result = new EstimateResult(new BigDecimal("123.45"),
            EstimateResult.SOURCE_RULE, 99L, null, "CNY", "按 1-3 件 30kg");
        when(billingEngineService.estimate(eq(ACTIVE_TENANT_ID), eq(ACTIVE_CUSTOMER_ID),
            eq("LAST_MILE"), anyMap())).thenReturn(result);

        var resp = controller.preview(req);
        assertTrue(resp.isSuccess());
        Map<String, Object> data = resp.getData();
        assertEquals(new BigDecimal("123.45"), data.get("amount"));
        assertEquals("RULE", data.get("source"));
        assertEquals(99L, data.get("ruleId"));
        assertEquals("CNY", data.get("currency"));
        assertEquals("按 1-3 件 30kg", data.get("breakdown"));

        // verify env passed to service
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> envCap = ArgumentCaptor.forClass(Map.class);
        verify(billingEngineService).estimate(eq(ACTIVE_TENANT_ID), eq(ACTIVE_CUSTOMER_ID),
            eq("LAST_MILE"), envCap.capture());
        Map<String, Object> env = envCap.getValue();
        assertEquals(req.totalWeightKg(), env.get("weight"));
        assertEquals(req.totalWeightKg(), env.get("totalWeightKg"));
        assertEquals(req.stopCount(), env.get("stopCount"));
    }

    @Test
    void preview_withoutCustomerContext_throwsCustomerException() {
        CustomerContext.clear();
        assertThrows(CustomerException.class, () -> controller.preview(sampleCreateRequest()));
    }

    // ================================================================
    //  create
    // ================================================================

    @Test
    @SuppressWarnings("unchecked")
    void create_callsServiceWithTenantCustomerAndMapsDoToResponse() {
        PortalCreateOrderRequest req = sampleCreateRequest();
        WmsDeliveryOrder order = new WmsDeliveryOrder();
        order.setId(100L);
        order.setNo("PO-abcd1234");
        order.setStatus("PENDING_DISPATCH");
        order.setSourceType("PORTAL");
        order.setWarehouseId(req.warehouseId());
        order.setCustomerId(ACTIVE_CUSTOMER_ID);
        order.setPlannedPickupWindowStart(req.plannedPickupWindowStart());
        order.setPlannedPickupWindowEnd(req.plannedPickupWindowEnd());
        when(deliveryOrderService.createForCustomer(anyLong(), anyLong(), any(PortalOrderRequest.class)))
            .thenReturn(order);

        var resp = controller.create(req);
        assertTrue(resp.isSuccess());
        Map<String, Object> data = resp.getData();
        assertEquals(100L, data.get("doId"));
        assertEquals("PO-abcd1234", data.get("doNo"));
        assertEquals("PENDING_DISPATCH", data.get("status"));
        assertEquals("PORTAL", data.get("sourceType"));
        assertEquals(req.warehouseId(), data.get("warehouseId"));

        // verify dispatch request fields mirror
        ArgumentCaptor<PortalOrderRequest> reqCap = ArgumentCaptor.forClass(PortalOrderRequest.class);
        verify(deliveryOrderService).createForCustomer(eq(ACTIVE_TENANT_ID), eq(ACTIVE_CUSTOMER_ID),
            reqCap.capture());
        PortalOrderRequest cap = reqCap.getValue();
        assertEquals(req.warehouseId(), cap.warehouseId());
        assertEquals(req.shipperAddress(), cap.shipperAddress());
        assertEquals(req.consigneeAddress(), cap.consigneeAddress());
        assertEquals(req.totalWeightKg(), cap.totalWeightKg());
        assertEquals(req.plannedPickupWindowStart(), cap.plannedPickupWindowStart());
    }

    @Test
    void create_pickupWindowTooShort_wrapsAsPortalCustomerException() {
        when(deliveryOrderService.createForCustomer(anyLong(), anyLong(), any(PortalOrderRequest.class)))
            .thenThrow(new PortalBadRequestException(400_002, "取件时间窗口起点非法 (太近)"));

        CustomerException ex = assertThrows(CustomerException.class,
            () -> controller.create(sampleCreateRequest()));
        // 400_002 in dispatch → ORDER_PICKUP_WINDOW_TOO_SHORT (code 400_002, http 400)
        assertEquals(400_002, ex.getCode());
        assertTrue(ex.getMessage().contains("取件时间窗口"));
    }

    @Test
    void create_pickupWindowInvalid_wrapsAsPortalCustomerException() {
        when(deliveryOrderService.createForCustomer(anyLong(), anyLong(), any(PortalOrderRequest.class)))
            .thenThrow(new PortalBadRequestException(400_001, "取件窗口终点非法 (end <= start)"));

        CustomerException ex = assertThrows(CustomerException.class,
            () -> controller.create(sampleCreateRequest()));
        // 400_001 → ORDER_PICKUP_WINDOW_INVALID (code 400_001)
        assertEquals(400_001, ex.getCode());
    }

    // ================================================================
    //  list
    // ================================================================

    @Test
    void list_callsServiceWithTrackQueryAndReturnsPageShape() {
        Page<Map<String, Object>> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(Map.of("doId", 100, "doNo", "DO-001")));
        when(deliveryOrderService.pageForCustomer(anyLong(), anyLong(), any())).thenReturn(page);

        var resp = controller.list("DO-001", "PENDING_DISPATCH", 1, 20);
        assertTrue(resp.isSuccess());
        Map<String, Object> data = resp.getData();
        assertEquals(1L, data.get("total"));
        assertEquals(1L, data.get("page"));
        assertEquals(20L, data.get("size"));
        assertNotNull(data.get("records"));

        verify(deliveryOrderService).pageForCustomer(eq(ACTIVE_TENANT_ID), eq(ACTIVE_CUSTOMER_ID), any());
    }

    // ================================================================
    //  detail
    // ================================================================

    @Test
    void detail_returnsRawServiceMap() {
        Map<String, Object> raw = Map.of("doId", 100L, "doNo", "DO-001", "status", "PENDING_DISPATCH");
        when(deliveryOrderService.getDetailForCustomer(anyLong(), anyLong(), eq(100L))).thenReturn(raw);

        var resp = controller.detail(100L);
        assertTrue(resp.isSuccess());
        assertEquals("DO-001", resp.getData().get("doNo"));
    }

    @Test
    void detail_notFound_wrapsAs404() {
        Long doId = 999L;
        when(deliveryOrderService.getDetailForCustomer(anyLong(), anyLong(), eq(doId)))
            .thenThrow(new DeliveryOrderNotFoundException(doId));

        CustomerException ex = assertThrows(CustomerException.class, () -> controller.detail(doId));
        assertEquals(404_001, ex.getCode(),
            "delivery 抛 DeliveryOrderNotFound → CustomerException(404_001) — uniform 404");
    }

    // ================================================================
    //  cancel
    // ================================================================

    @Test
    void cancel_callsServiceWithReasonAndReturnsUpdatedDo() {
        WmsDeliveryOrder cancelled = new WmsDeliveryOrder();
        cancelled.setId(100L);
        cancelled.setNo("PO-abcd1234");
        cancelled.setStatus("CANCELLED");
        cancelled.setSourceType("PORTAL");
        when(deliveryOrderService.cancelForCustomer(anyLong(), anyLong(), eq(100L), eq("客户取消")))
            .thenReturn(cancelled);

        var resp = controller.cancel(100L, new PortalCancelOrderRequest("客户取消"));
        assertTrue(resp.isSuccess());
        assertEquals("CANCELLED", resp.getData().get("status"));
        verify(deliveryOrderService).cancelForCustomer(
            eq(ACTIVE_TENANT_ID), eq(ACTIVE_CUSTOMER_ID), eq(100L), eq("客户取消"));
    }

    @Test
    void cancel_notFound_wrapsAs404() {
        Long doId = 999L;
        when(deliveryOrderService.cancelForCustomer(anyLong(), anyLong(), eq(doId), anyString()))
            .thenThrow(new DeliveryOrderNotFoundException(doId));

        CustomerException ex = assertThrows(CustomerException.class,
            () -> controller.cancel(doId, new PortalCancelOrderRequest("客户取消")));
        assertEquals(404_001, ex.getCode());
    }

    @Test
    void cancel_notCancellable_wrapsAs409() {
        // 409_001 from dispatch → ORDER_NOT_CANCELLABLE in PortalErrorCode (code 409_001)
        when(deliveryOrderService.cancelForCustomer(anyLong(), anyLong(), anyLong(), anyString()))
            .thenThrow(new PortalBadRequestException(409_001, "订单当前状态不可取消"));

        CustomerException ex = assertThrows(CustomerException.class,
            () -> controller.cancel(100L, new PortalCancelOrderRequest("客户取消")));
        assertEquals(409_001, ex.getCode());
    }

    // ================================================================
    //  tenant context
    // ================================================================

    @Test
    void anyEndpoint_withoutTenantContext_throwsCustomerException() {
        TenantContext.clear();
        assertThrows(CustomerException.class, () -> controller.preview(sampleCreateRequest()));
        assertThrows(CustomerException.class, () -> controller.detail(100L));
    }

    // ================================================================
    //  helpers
    // ================================================================

    private static PortalCreateOrderRequest sampleCreateRequest() {
        return new PortalCreateOrderRequest(
            50L,                                            // warehouseId
            "上海市浦东新区张江路 1 号",                       // shipperAddress
            "上海市徐汇区漕河泾路 100 号",                     // consigneeAddress
            new BigDecimal("30.50"),                          // totalWeightKg
            new BigDecimal("0.80"),                           // totalVolumeM3
            2,                                               // stopCount
            LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0),
            LocalDateTime.now().plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0),
            "客户备注: 易碎"                                  // remark
        );
    }
}
