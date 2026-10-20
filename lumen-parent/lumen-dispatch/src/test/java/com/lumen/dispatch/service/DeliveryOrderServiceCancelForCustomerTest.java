package com.lumen.dispatch.service;

import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.engine.LoadingValidator;
import com.lumen.dispatch.engine.RoutingEngine;
import com.lumen.dispatch.entity.WmsDeliveryOrder;
import com.lumen.dispatch.exception.DeliveryOrderNotFoundException;
import com.lumen.dispatch.exception.PortalBadRequestException;
import com.lumen.dispatch.mapper.WmsDeliveryExceptionMapper;
import com.lumen.dispatch.mapper.WmsDeliveryOrderMapper;
import com.lumen.dispatch.mapper.WmsDeliveryStopMapper;
import com.lumen.dispatch.mapper.WmsVehicleMapper;
import com.lumen.dispatch.service.impl.DeliveryOrderServiceImpl;
import com.lumen.extension.outbox.DomainEvent;
import com.lumen.extension.outbox.EventBus;
import com.lumen.extension.state.StateMachineEngine;
import com.lumen.finance.service.BillingEngineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeliveryOrderService.cancelForCustomer 单元测试 (Iteration 4.5B.2 T2.4).
 *
 * <p>覆盖 5 个场景:
 * <ul>
 *   <li>happy path: PENDING_DISPATCH → CANCELLED, 改 status + remark 后缀 + 发事件</li>
 *   <li>DISPATCHED 状态: 抛 PortalBadRequestException(409_001), 不调 update, 不发事件</li>
 *   <li>跨客户访问: 抛 DeliveryOrderNotFoundException (uniform 404)</li>
 *   <li>跨租户访问: 抛 DeliveryOrderNotFoundException (uniform 404)</li>
 *   <li>DO 不存在: 抛 DeliveryOrderNotFoundException</li>
 * </ul>
 *
 * <p>uniform 404 (跨租户 / 跨客户 / 不存在) — 避免泄漏存在性, 也防越权.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DeliveryOrderService.cancelForCustomer 单元测试 (4.5B.2 T2.4)")
class DeliveryOrderServiceCancelForCustomerTest {

    @Mock private WmsDeliveryOrderMapper orderMapper;
    @Mock private WmsDeliveryStopMapper stopMapper;
    @Mock private WmsDeliveryExceptionMapper exceptionMapper;
    @Mock private WmsVehicleMapper vehicleMapper;
    @Mock private LoadingValidator loadingValidator;
    @Mock private RoutingEngine routingEngine;
    @Mock private StateMachineEngine stateMachineEngine;
    @Mock private EventBus eventBus;
    @Mock private BillingEngineService billingEngineService;

    private DeliveryOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L);
        service = new DeliveryOrderServiceImpl(
                orderMapper,
                stopMapper,
                exceptionMapper,
                vehicleMapper,
                loadingValidator,
                routingEngine,
                stateMachineEngine,
                eventBus,
                billingEngineService
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("happy path: PENDING_DISPATCH → CANCELLED, 改 status + remark 后缀 + 发 DeliveryOrderStateChangedEvent")
    void cancelForCustomer_pendingOrder_returnsCancelled() {
        WmsDeliveryOrder order = new WmsDeliveryOrder();
        order.setId(100L);
        order.setTenantId(1L);
        order.setCustomerId(42L);
        order.setStatus(DispatchConstants.DO_PENDING_DISPATCH);
        order.setRemark("原备注");
        when(orderMapper.selectById(100L)).thenReturn(order);
        when(orderMapper.updateById(any(WmsDeliveryOrder.class))).thenReturn(1);

        WmsDeliveryOrder result = service.cancelForCustomer(1L, 42L, 100L, "客户改主意");

        assertEquals(DispatchConstants.DO_CANCELLED, result.getStatus());
        assertThat(result.getRemark()).contains("原备注");
        assertThat(result.getRemark()).contains("客户改主意");
        assertThat(result.getRemark()).contains("42");
        verify(orderMapper, times(1)).updateById(any(WmsDeliveryOrder.class));
        verify(eventBus, times(1)).publish(any(DomainEvent.class));
    }

    @Test
    @DisplayName("DISPATCHED 状态: 抛 PortalBadRequestException(409_001), 不调 update, 不发事件")
    void cancelForCustomer_dispatchedOrder_throwsNotCancellable() {
        WmsDeliveryOrder order = new WmsDeliveryOrder();
        order.setId(100L);
        order.setTenantId(1L);
        order.setCustomerId(42L);
        order.setStatus(DispatchConstants.DO_DISPATCHED);
        when(orderMapper.selectById(100L)).thenReturn(order);

        PortalBadRequestException ex = assertThrows(PortalBadRequestException.class,
                () -> service.cancelForCustomer(1L, 42L, 100L, "客户改主意"));
        assertEquals(409_001, ex.getCode(), "非 PENDING_DISPATCH → 409_001 ORDER_NOT_CANCELLABLE");
        verify(orderMapper, never()).updateById(any(WmsDeliveryOrder.class));
        verify(eventBus, never()).publish(any(DomainEvent.class));
    }

    @Test
    @DisplayName("跨客户访问: 抛 DeliveryOrderNotFoundException (uniform 404, 不泄漏存在性)")
    void cancelForCustomer_otherCustomersOrder_throwsNotFound() {
        WmsDeliveryOrder order = new WmsDeliveryOrder();
        order.setId(100L);
        order.setTenantId(1L);
        order.setCustomerId(99L);    // 实际是 99, 但调用方传 42
        order.setStatus(DispatchConstants.DO_PENDING_DISPATCH);
        when(orderMapper.selectById(100L)).thenReturn(order);

        assertThrows(DeliveryOrderNotFoundException.class,
                () -> service.cancelForCustomer(1L, 42L, 100L, "客户改主意"));
        verify(orderMapper, never()).updateById(any(WmsDeliveryOrder.class));
        verify(eventBus, never()).publish(any(DomainEvent.class));
    }

    @Test
    @DisplayName("跨租户访问: 抛 DeliveryOrderNotFoundException (uniform 404)")
    void cancelForCustomer_crossTenant_throwsNotFound() {
        WmsDeliveryOrder order = new WmsDeliveryOrder();
        order.setId(100L);
        order.setTenantId(2L);       // 实际是 tenant=2, 但调用方传 tenant=1
        order.setCustomerId(42L);
        order.setStatus(DispatchConstants.DO_PENDING_DISPATCH);
        when(orderMapper.selectById(100L)).thenReturn(order);

        assertThrows(DeliveryOrderNotFoundException.class,
                () -> service.cancelForCustomer(1L, 42L, 100L, "客户改主意"));
        verify(orderMapper, never()).updateById(any(WmsDeliveryOrder.class));
        verify(eventBus, never()).publish(any(DomainEvent.class));
    }

    @Test
    @DisplayName("DO 不存在: 抛 DeliveryOrderNotFoundException")
    void cancelForCustomer_notFound_throwsNotFound() {
        when(orderMapper.selectById(100L)).thenReturn(null);

        assertThrows(DeliveryOrderNotFoundException.class,
                () -> service.cancelForCustomer(1L, 42L, 100L, "客户改主意"));
        verify(orderMapper, never()).updateById(any(WmsDeliveryOrder.class));
        verify(eventBus, never()).publish(any(DomainEvent.class));
    }
}