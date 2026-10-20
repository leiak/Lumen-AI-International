package com.lumen.dispatch.service;

import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.dto.PortalOrderRequest;
import com.lumen.dispatch.engine.LoadingValidator;
import com.lumen.dispatch.engine.RoutingEngine;
import com.lumen.dispatch.entity.WmsDeliveryOrder;
import com.lumen.dispatch.event.DeliveryOrderStateChangedEvent;
import com.lumen.dispatch.exception.PortalBadRequestException;
import com.lumen.dispatch.mapper.WmsDeliveryExceptionMapper;
import com.lumen.dispatch.mapper.WmsDeliveryOrderMapper;
import com.lumen.dispatch.mapper.WmsDeliveryStopMapper;
import com.lumen.dispatch.mapper.WmsVehicleMapper;
import com.lumen.dispatch.service.impl.DeliveryOrderServiceImpl;
import com.lumen.extension.outbox.DomainEvent;
import com.lumen.extension.outbox.EventBus;
import com.lumen.extension.state.StateMachineEngine;
import com.lumen.finance.dto.BillingEventDto;
import com.lumen.finance.service.BillingEngineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeliveryOrderService.createForCustomer 单元测试 (Iteration 4.5B.2 T2.3).
 *
 * <p>覆盖 3 个场景:
 * <ul>
 *   <li>happy path: 持久化 PENDING_DISPATCH DO + 调 estimateAndAccrue + 发状态变更事件</li>
 *   <li>billing 失败: DO 已写入, 异常不抛出 (关键路径不阻塞)</li>
 *   <li>取件窗口非法: 抛 PortalBadRequestException, 不调 mapper.insert</li>
 * </ul>
 *
 * <p>使用 {@code @MockitoSettings(strictness=LENIENT)} 与 dispatch 模块既有
 * 测试一致 — setUp 中大量 stub, 但具体测试只走子集, strict 模式会报 unstubbed.
 *
 * <p>9 个构造函数参数全部走 {@link Mock} 注入 — 不强制 {@code @InjectMocks}
 * 单字段匹配, 9 个 mock 字段名与构造参数名一一对应, MockitoExtension 自动 wiring.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DeliveryOrderService.createForCustomer 单元测试 (4.5B.2 T2.3)")
class DeliveryOrderServiceCreateForCustomerTest {

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
    @DisplayName("happy path: 持久化 PENDING_DISPATCH DO + 调 estimateAndAccrue + 发状态变更事件")
    void createForCustomer_happyPath_persistsPendingDispatchAndCallsEstimate() {
        PortalOrderRequest req = new PortalOrderRequest(
                10L,                                       // warehouseId
                "shipper addr 北京海淀",                       // shipperAddress
                "consignee addr 上海浦东",                     // consigneeAddress
                new BigDecimal("20"),                       // totalWeightKg
                new BigDecimal("0.5"),                      // totalVolumeM3
                2,                                          // stopCount
                LocalDateTime.now().plusHours(3),           // plannedPickupWindowStart (>= now+2h)
                LocalDateTime.now().plusHours(6),           // plannedPickupWindowEnd (> start, <= +8h, 同日)
                "客户备注"                                    // remark
        );
        // mapper.insert 后让返回值 1, 并把 id 写回 — 模拟 MyBatis-Plus 的回填
        when(orderMapper.insert(any(WmsDeliveryOrder.class))).thenAnswer(inv -> {
            WmsDeliveryOrder arg = inv.getArgument(0);
            arg.setId(999L);
            return 1;
        });

        WmsDeliveryOrder result = service.createForCustomer(1L, 42L, req);

        assertNotNull(result);
        assertEquals(999L, result.getId(), "mapper.insert 后 ID 已回填");
        assertEquals(DispatchConstants.DO_PENDING_DISPATCH, result.getStatus());
        assertEquals(42L, result.getCustomerId());
        assertEquals(1L, result.getTenantId());
        assertNotNull(result.getNo(), "DO no 不能为空");
        assertTrue(result.getNo().startsWith("PO-"), "DO no 应以 PO- 开头 (portal 订单)");
        assertEquals(10L, result.getWarehouseId());
        assertEquals("PORTAL", result.getSourceType());

        // 取件窗口从 DTO 直接搬过来 (end 已给定, 用值不用默认)
        assertEquals(req.plannedPickupWindowStart(), result.getPlannedPickupWindowStart());
        assertEquals(req.plannedPickupWindowEnd(), result.getPlannedPickupWindowEnd());
        assertThat(result.getRemark()).contains("客户备注");
        assertThat(result.getRemark()).contains("收件地址");

        // 1. mapper.insert 被调一次
        verify(orderMapper, times(1)).insert(any(WmsDeliveryOrder.class));
        // 2. billing.estimateAndAccrue 被调一次, 入参 sourceEventId = doId
        ArgumentCaptor<BillingEventDto> billingCap = ArgumentCaptor.forClass(BillingEventDto.class);
        verify(billingEngineService, times(1)).estimateAndAccrue(billingCap.capture());
        BillingEventDto billed = billingCap.getValue();
        assertEquals(1L, billed.tenantId());
        assertEquals("999", billed.sourceEventId(), "sourceEventId 应等于 doId 字符串");
        assertEquals("LAST_MILE", billed.businessType());
        assertEquals("RECEIVABLE", billed.direction());
        assertEquals(42L, billed.counterpartyId());
        assertEquals("CUSTOMER", billed.counterpartyType());
        assertEquals(new BigDecimal("20"), billed.env().get("totalWeightKg"));
        // 3. eventBus.publish 被调一次, 是 DeliveryOrderStateChangedEvent + action=create + toState=PENDING_DISPATCH
        ArgumentCaptor<DomainEvent> evtCap = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventBus, times(1)).publish(evtCap.capture());
        assertThat(evtCap.getValue()).isInstanceOf(DeliveryOrderStateChangedEvent.class);
        DeliveryOrderStateChangedEvent doEvt = (DeliveryOrderStateChangedEvent) evtCap.getValue();
        assertEquals(999L, doEvt.getOrderId());
        assertEquals(DispatchConstants.DO_PENDING_DISPATCH, doEvt.getToState());
        assertEquals("create", doEvt.getAction());
    }

    @Test
    @DisplayName("billing 失败: DO 仍持久化, 不抛异常 (关键路径不阻塞)")
    void createForCustomer_billingFails_doesNotBlockDoInsert() {
        PortalOrderRequest req = new PortalOrderRequest(
                10L,
                "shipper",
                "consignee",
                new BigDecimal("20"),
                new BigDecimal("0.5"),
                1,
                LocalDateTime.now().plusHours(3),
                null,                          // end null → 用 start+4h 兜底
                null
        );
        when(orderMapper.insert(any(WmsDeliveryOrder.class))).thenAnswer(inv -> {
            WmsDeliveryOrder arg = inv.getArgument(0);
            arg.setId(998L);
            return 1;
        });
        doThrow(new RuntimeException("finance service down"))
                .when(billingEngineService).estimateAndAccrue(any());

        // 关键: 这里断言不抛 — DO 创建是关键路径, finance 失败由后台重试
        WmsDeliveryOrder result = assertDoesNotThrow(
                () -> service.createForCustomer(1L, 42L, req));

        assertEquals(998L, result.getId());
        assertEquals(DispatchConstants.DO_PENDING_DISPATCH, result.getStatus());
        // end 为 null → service 兜底为 start + 4h
        LocalDateTime expectedEnd = req.plannedPickupWindowStart().plusHours(4);
        assertEquals(expectedEnd, result.getPlannedPickupWindowEnd());

        // mapper 仍被调用 (DO 已持久化)
        verify(orderMapper, times(1)).insert(any(WmsDeliveryOrder.class));
        // billing 被调用过 (失败被吞)
        verify(billingEngineService, times(1)).estimateAndAccrue(any());
        // event 仍发布 (PENDING_DISPATCH 初始化事件)
        verify(eventBus, times(1)).publish(any(DomainEvent.class));
    }

    @Test
    @DisplayName("取件窗口起点 < now+2h → 抛 PortalBadRequestException(400_002), 不调 mapper.insert")
    void createForCustomer_pickupWindowTooSoon_throwsBadRequest() {
        PortalOrderRequest req = new PortalOrderRequest(
                10L,
                "shipper",
                "consignee",
                new BigDecimal("20"),
                new BigDecimal("0.5"),
                1,
                LocalDateTime.now().plusHours(1),    // < 2h ahead → invalid (start too soon)
                null,
                null
        );

        PortalBadRequestException ex = assertThrows(PortalBadRequestException.class,
                () -> service.createForCustomer(1L, 42L, req));
        assertEquals(400_002, ex.getCode(), "起点太近 → 400_002 (取件窗口起点非法)");
        assertThat(ex.getMessage()).contains("起点");

        verify(orderMapper, never()).insert(any(WmsDeliveryOrder.class));
        verify(billingEngineService, never()).estimateAndAccrue(any());
        verify(eventBus, never()).publish(any(DomainEvent.class));
    }
}
