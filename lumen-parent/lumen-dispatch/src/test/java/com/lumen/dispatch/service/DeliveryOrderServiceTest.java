package com.lumen.dispatch.service;

import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.dto.DeliveryOrderDtos.AssignRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.CancelRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.CreateDeliveryOrderRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.DepartRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.LoadRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.RaiseExceptionRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.ResolveExceptionRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.ResolveStrategy;
import com.lumen.dispatch.dto.DeliveryOrderDtos.SignRequest;
import com.lumen.dispatch.engine.LoadingValidator;
import com.lumen.dispatch.engine.RoutingEngine;
import com.lumen.dispatch.entity.WmsDeliveryException;
import com.lumen.dispatch.entity.WmsDeliveryOrder;
import com.lumen.dispatch.entity.WmsDeliveryStop;
import com.lumen.dispatch.entity.WmsVehicle;
import com.lumen.dispatch.exception.DeliveryOrderNotFoundException;
import com.lumen.dispatch.exception.DeliveryStopNotFoundException;
import com.lumen.dispatch.exception.DispatchException;
import com.lumen.dispatch.exception.IllegalDeliveryOrderStateException;
import com.lumen.dispatch.exception.InsufficientVehicleException;
import com.lumen.dispatch.exception.VehicleNotFoundException;
import com.lumen.dispatch.mapper.WmsDeliveryExceptionMapper;
import com.lumen.dispatch.mapper.WmsDeliveryOrderMapper;
import com.lumen.dispatch.mapper.WmsDeliveryStopMapper;
import com.lumen.dispatch.mapper.WmsVehicleMapper;
import com.lumen.dispatch.service.impl.DeliveryOrderServiceImpl;
import com.lumen.extension.state.StateMachineEngine;
import com.lumen.extension.state.StateMachineException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeliveryOrderService 单元测试 (Iteration 4.1 PR4 + 4.1.x PR1).
 *
 * <p>覆盖 8 个状态机迁移 + CRUD + assign/raiseException 边界 +
 * resolveException (PR1 新增 4 cases).
 *
 * <p>使用 {@code @MockitoSettings(strictness=LENIENT)} 因为本测试在
 * setUp 里做了大量 mock stubbing，但具体测试只调用子集；strict 模式
 * 会把所有未触达的 stubbing 当作错误。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DeliveryOrderService 单元测试")
class DeliveryOrderServiceTest {

    @Mock private WmsDeliveryOrderMapper orderMapper;
    @Mock private WmsDeliveryStopMapper stopMapper;
    @Mock private WmsDeliveryExceptionMapper exceptionMapper;
    @Mock private WmsVehicleMapper vehicleMapper;
    @Mock private LoadingValidator loadingValidator;
    @Mock private RoutingEngine routingEngine;
    @Mock private StateMachineEngine stateMachineEngine;

    @InjectMocks private DeliveryOrderServiceImpl service;

    private WmsDeliveryOrder order;
    private WmsVehicle vehicle;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L);
        order = new WmsDeliveryOrder();
        order.setId(100L);
        order.setTenantId(1L);
        order.setNo("DO-TEST-100");
        order.setSourceNo("OB-001");
        order.setStatus(DispatchConstants.DO_PENDING_DISPATCH);
        order.setWarehouseId(1L);
        order.setTotalWeightKg(new BigDecimal("100"));
        order.setTotalVolumeM3(new BigDecimal("1"));
        order.setStopCount(2);
        lenient().when(orderMapper.selectById(100L)).thenReturn(order);
        lenient().when(orderMapper.selectById(99L)).thenReturn(null);

        vehicle = new WmsVehicle();
        vehicle.setId(200L);
        vehicle.setStatus(DispatchConstants.VEH_IDLE);
        vehicle.setMaxLoadKg(new BigDecimal("500"));
        vehicle.setMaxVolumeM3(new BigDecimal("5"));
        vehicle.setDriverId(300L);
        lenient().when(vehicleMapper.selectById(200L)).thenReturn(vehicle);
        lenient().when(vehicleMapper.selectById(999L)).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ====================================================================
    // CRUD
    // ====================================================================

    @Test
    @DisplayName("create: 默认 PENDING_DISPATCH + tenant 回填 + 自动生成 no")
    void create_defaults() {
        CreateDeliveryOrderRequest req = new CreateDeliveryOrderRequest();
        req.setSourceNo("OB-XYZ");
        req.setWarehouseId(1L);
        req.setTotalWeightKg(new BigDecimal("50"));
        req.setTotalVolumeM3(new BigDecimal("0.5"));
        req.setStopCount(1);

        when(orderMapper.insert(any(WmsDeliveryOrder.class))).thenAnswer(inv -> {
            WmsDeliveryOrder arg = inv.getArgument(0);
            arg.setId(555L);
            return 1;
        });

        WmsDeliveryOrder result = service.create(req);

        assertThat(result.getStatus()).isEqualTo(DispatchConstants.DO_PENDING_DISPATCH);
        assertThat(result.getTenantId()).isEqualTo(1L);
        assertThat(result.getNo()).startsWith("DO-");
        assertThat(result.getSourceType()).isEqualTo("OUTBOUND");
    }

    @Test
    @DisplayName("get: id 不存在 → DeliveryOrderNotFoundException")
    void get_notFound() {
        assertThatThrownBy(() -> service.get(99L))
                .isInstanceOf(DeliveryOrderNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("update: 跳过 id/tenant/no/status/vehicle/driver/sourceNo 等不可变字段")
    void update_skipsImmutableFields() {
        com.lumen.dispatch.dto.DeliveryOrderDtos.UpdateDeliveryOrderRequest req =
                new com.lumen.dispatch.dto.DeliveryOrderDtos.UpdateDeliveryOrderRequest();
        req.setRemark("new remark");
        req.setTotalWeightKg(new BigDecimal("200"));

        WmsDeliveryOrder result = service.update(100L, req);

        assertThat(result.getRemark()).isEqualTo("new remark");
        assertThat(result.getTotalWeightKg()).isEqualByComparingTo("200");
        assertThat(result.getNo()).isEqualTo("DO-TEST-100");
        assertThat(result.getStatus()).isEqualTo(DispatchConstants.DO_PENDING_DISPATCH);
        assertThat(result.getSourceNo()).isEqualTo("OB-001");
        verify(orderMapper).updateById(order);
    }

    // ====================================================================
    // State machine — happy paths
    // ====================================================================

    @Test
    @DisplayName("assign: PENDING_DISPATCH → DISPATCHED + 车辆 IDLE → DISPATCHED")
    void assign_pendingToDispatched() {
        AssignRequest req = new AssignRequest();
        req.setVehicleId(200L);
        req.setDriverId(300L);

        WmsDeliveryOrder result = service.assign(100L, req);

        assertThat(result.getStatus()).isEqualTo(DispatchConstants.DO_DISPATCHED);
        assertThat(result.getVehicleId()).isEqualTo(200L);
        assertThat(result.getDriverId()).isEqualTo(300L);
        assertThat(vehicle.getStatus()).isEqualTo(DispatchConstants.VEH_DISPATCHED);
        verify(loadingValidator).validate(vehicle, order);
    }

    @Test
    @DisplayName("load: DISPATCHED → LOADED")
    void load_dispatchedToLoaded() {
        order.setStatus(DispatchConstants.DO_DISPATCHED);

        WmsDeliveryOrder result = service.load(100L, new LoadRequest());

        assertThat(result.getStatus()).isEqualTo(DispatchConstants.DO_LOADED);
    }

    @Test
    @DisplayName("depart: LOADED → ENROUTE + actualDepartAt")
    void depart_loadedToEnroute() {
        order.setStatus(DispatchConstants.DO_LOADED);

        WmsDeliveryOrder result = service.depart(100L, new DepartRequest());

        assertThat(result.getStatus()).isEqualTo(DispatchConstants.DO_ENROUTE);
        assertThat(result.getActualDepartAt()).isNotNull();
    }

    @Test
    @DisplayName("arrive: ENROUTE → ARRIVED + stop.arrived_at")
    void arrive_enrouteToArrived() {
        order.setStatus(DispatchConstants.DO_ENROUTE);
        WmsDeliveryStop stop = new WmsDeliveryStop();
        stop.setId(10L);
        stop.setOrderId(100L);
        when(stopMapper.selectById(10L)).thenReturn(stop);

        com.lumen.dispatch.dto.DeliveryOrderDtos.ArriveRequest req =
                new com.lumen.dispatch.dto.DeliveryOrderDtos.ArriveRequest();
        req.setStopId(10L);

        WmsDeliveryOrder result = service.arrive(100L, req);

        assertThat(result.getStatus()).isEqualTo(DispatchConstants.DO_ARRIVED);
        assertThat(stop.getStatus()).isEqualTo("ARRIVED");
        assertThat(stop.getArrivedAt()).isNotNull();
    }

    @Test
    @DisplayName("sign: ARRIVED → SIGNED + signPhotoUrl + stop.completed_at")
    void sign_arrivedToSigned() {
        order.setStatus(DispatchConstants.DO_ARRIVED);
        WmsDeliveryStop stop = new WmsDeliveryStop();
        stop.setId(10L);
        stop.setOrderId(100L);
        when(stopMapper.selectById(10L)).thenReturn(stop);

        SignRequest req = new SignRequest();
        req.setStopId(10L);
        req.setSignPhotoUrl("http://example.com/photo.jpg");

        WmsDeliveryOrder result = service.sign(100L, req);

        assertThat(result.getStatus()).isEqualTo(DispatchConstants.DO_SIGNED);
        assertThat(result.getSignPhotoUrl()).isEqualTo("http://example.com/photo.jpg");
        assertThat(result.getDeliveredAt()).isNotNull();
        assertThat(stop.getStatus()).isEqualTo("COMPLETED");
        assertThat(stop.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("cancel: PENDING_DISPATCH → CANCELLED + 备注拼上 reason")
    void cancel_pendingToCancelled() {
        CancelRequest req = new CancelRequest();
        req.setReason("客户取消");

        WmsDeliveryOrder result = service.cancel(100L, req);

        assertThat(result.getStatus()).isEqualTo(DispatchConstants.DO_CANCELLED);
        assertThat(result.getRemark()).contains("CANCELLED: 客户取消");
    }

    @Test
    @DisplayName("raiseException: PENDING_DISPATCH → EXCEPTION + 新建 OPEN 异常工单")
    void raiseException_pendingToException() {
        RaiseExceptionRequest req = new RaiseExceptionRequest();
        req.setType("REJECTED");
        req.setDescription("客户拒收");

        when(exceptionMapper.insert(any(WmsDeliveryException.class))).thenAnswer(inv -> {
            WmsDeliveryException arg = inv.getArgument(0);
            arg.setId(900L);
            return 1;
        });

        WmsDeliveryException ex = service.raiseException(100L, req);

        assertThat(ex.getStatus()).isEqualTo(DispatchConstants.EX_OPEN);
        assertThat(ex.getOrderId()).isEqualTo(100L);
        assertThat(ex.getType()).isEqualTo("REJECTED");
        verify(orderMapper).updateById(order);
        assertThat(order.getExceptionId()).isEqualTo(900L);
        assertThat(order.getStatus()).isEqualTo(DispatchConstants.DO_EXCEPTION);
    }

    // ====================================================================
    // State machine — failure paths
    // ====================================================================

    @Test
    @DisplayName("assign: 车辆不存在 → VehicleNotFoundException")
    void assign_vehicleNotFound() {
        AssignRequest req = new AssignRequest();
        req.setVehicleId(999L);
        req.setDriverId(300L);

        assertThatThrownBy(() -> service.assign(100L, req))
                .isInstanceOf(VehicleNotFoundException.class)
                .hasMessageContaining("999");
        verify(loadingValidator, never()).validate(any(), any());
    }

    @Test
    @DisplayName("assign: 容量不足 → InsufficientVehicleException")
    void assign_insufficient() {
        doThrow(new InsufficientVehicleException("over weight"))
                .when(loadingValidator).validate(any(), any());

        AssignRequest req = new AssignRequest();
        req.setVehicleId(200L);
        req.setDriverId(300L);

        assertThatThrownBy(() -> service.assign(100L, req))
                .isInstanceOf(InsufficientVehicleException.class);
        verify(orderMapper, never()).updateById(any(WmsDeliveryOrder.class));
    }

    @Test
    @DisplayName("load: 当前 SIGNED (终态) → StateMachineException 透传")
    void load_illegalState() {
        order.setStatus(DispatchConstants.DO_SIGNED);
        doThrow(new StateMachineException("DISPATCH_ORDER SIGNED -> LOADED not allowed"))
                .when(stateMachineEngine).assertTransition(
                        DispatchConstants.SM_DISPATCH_ORDER,
                        DispatchConstants.DO_SIGNED, DispatchConstants.DO_LOADED);

        assertThatThrownBy(() -> service.load(100L, new LoadRequest()))
                .isInstanceOf(StateMachineException.class);
        verify(orderMapper, never()).updateById(any(WmsDeliveryOrder.class));
    }

    @Test
    @DisplayName("arrive: stop 不属于本订单 → DeliveryStopNotFoundException")
    void arrive_stopNotBelong() {
        order.setStatus(DispatchConstants.DO_ENROUTE);
        WmsDeliveryStop stop = new WmsDeliveryStop();
        stop.setId(10L);
        stop.setOrderId(999L);  // other order
        when(stopMapper.selectById(10L)).thenReturn(stop);

        com.lumen.dispatch.dto.DeliveryOrderDtos.ArriveRequest req =
                new com.lumen.dispatch.dto.DeliveryOrderDtos.ArriveRequest();
        req.setStopId(10L);

        assertThatThrownBy(() -> service.arrive(100L, req))
                .isInstanceOf(DeliveryStopNotFoundException.class);
    }

    @Test
    @DisplayName("cancel: 终态 CANCELLED → StateMachineException")
    void cancel_fromTerminalRejected() {
        order.setStatus(DispatchConstants.DO_CANCELLED);
        doThrow(new StateMachineException("already cancelled"))
                .when(stateMachineEngine).assertTransition(
                        DispatchConstants.SM_DISPATCH_ORDER,
                        DispatchConstants.DO_CANCELLED, DispatchConstants.DO_CANCELLED);

        CancelRequest req = new CancelRequest();
        req.setReason("x");

        assertThatThrownBy(() -> service.cancel(100L, req))
                .isInstanceOf(StateMachineException.class);
    }

    // ====================================================================
    // resolveException (Iteration 4.1.x PR1)
    // ====================================================================

    @Test
    @DisplayName("resolveException: RE_DISPATCH — EXCEPTION → DISPATCHED + 换车 IDLE → DISPATCHED")
    void resolveException_RE_DISPATCH_assignsNewVehicle() {
        order.setStatus(DispatchConstants.DO_EXCEPTION);
        // 准备一辆 IDLE 车辆, 模拟"换车重新派"场景
        WmsVehicle newVehicle = new WmsVehicle();
        newVehicle.setId(500L);
        newVehicle.setStatus(DispatchConstants.VEH_IDLE);
        newVehicle.setMaxLoadKg(new BigDecimal("1000"));
        newVehicle.setMaxVolumeM3(new BigDecimal("10"));
        newVehicle.setDriverId(700L);
        when(vehicleMapper.selectById(500L)).thenReturn(newVehicle);

        ResolveExceptionRequest req = new ResolveExceptionRequest();
        req.setStrategy(ResolveStrategy.RE_DISPATCH);
        req.setVehicleId(500L);
        req.setDriverId(700L);
        req.setReason("vehicle 200 broke down on highway, switching to spare");

        WmsDeliveryOrder result = service.resolveException(100L, req);

        assertThat(result.getStatus()).isEqualTo(DispatchConstants.DO_DISPATCHED);
        assertThat(result.getVehicleId()).isEqualTo(500L);
        assertThat(result.getDriverId()).isEqualTo(700L);
        assertThat(result.getExceptionId()).isNull();  // reDispatch 清掉 exception_id 引用
        assertThat(result.getRemark()).contains("RE_DISPATCHED:");
        // 状态机迁移 + 车辆状态机迁移
        verify(stateMachineEngine).assertTransition(
                eq(DispatchConstants.SM_DISPATCH_ORDER),
                eq(DispatchConstants.DO_EXCEPTION),
                eq(DispatchConstants.DO_DISPATCHED));
        verify(loadingValidator).validate(newVehicle, order);
        verify(vehicleMapper).updateById(any(WmsVehicle.class));
    }

    @Test
    @DisplayName("resolveException: RE_DISPATCH — 车辆容量不足 → InsufficientVehicleException")
    void resolveException_RE_DISPATCH_invalidVehicle_throwsInsufficientVehicle() {
        order.setStatus(DispatchConstants.DO_EXCEPTION);
        WmsVehicle newVehicle = new WmsVehicle();
        newVehicle.setId(500L);
        newVehicle.setStatus(DispatchConstants.VEH_IDLE);
        newVehicle.setMaxLoadKg(new BigDecimal("100"));
        newVehicle.setMaxVolumeM3(new BigDecimal("1"));
        newVehicle.setDriverId(700L);
        when(vehicleMapper.selectById(500L)).thenReturn(newVehicle);

        doThrow(new InsufficientVehicleException("over weight"))
                .when(loadingValidator).validate(any(WmsVehicle.class), any(WmsDeliveryOrder.class));

        ResolveExceptionRequest req = new ResolveExceptionRequest();
        req.setStrategy(ResolveStrategy.RE_DISPATCH);
        req.setVehicleId(500L);
        req.setDriverId(700L);

        assertThatThrownBy(() -> service.resolveException(100L, req))
                .isInstanceOf(InsufficientVehicleException.class);

        // 容量校验失败 → 订单不会被 update, 车辆也不会被 update
        verify(orderMapper, never()).updateById(any(WmsDeliveryOrder.class));
        verify(vehicleMapper, never()).updateById(any(WmsVehicle.class));
    }

    @Test
    @DisplayName("resolveException: CANCEL — EXCEPTION → CANCELLED + reason 拼到 remark")
    void resolveException_CANCEL_fromException_succeeds() {
        order.setStatus(DispatchConstants.DO_EXCEPTION);
        order.setRemark("货损严重, 客户要求终止");

        ResolveExceptionRequest req = new ResolveExceptionRequest();
        req.setStrategy(ResolveStrategy.CANCEL);
        req.setReason("客户已拒收并退款, 关闭工单");

        WmsDeliveryOrder result = service.resolveException(100L, req);

        assertThat(result.getStatus()).isEqualTo(DispatchConstants.DO_CANCELLED);
        assertThat(result.getRemark()).contains("货损严重, 客户要求终止");
        assertThat(result.getRemark()).contains("EXCEPTION_CANCELLED: 客户已拒收并退款, 关闭工单");
        verify(stateMachineEngine).assertTransition(
                eq(DispatchConstants.SM_DISPATCH_ORDER),
                eq(DispatchConstants.DO_EXCEPTION),
                eq(DispatchConstants.DO_CANCELLED));
        // CANCEL 路径不会触碰车辆
        verify(vehicleMapper, never()).selectById(any());
        verify(vehicleMapper, never()).updateById(any(WmsVehicle.class));
    }

    @Test
    @DisplayName("resolveException: 非 EXCEPTION 状态 → IllegalDeliveryOrderStateException")
    void resolveException_invalidStrategy_throws() {
        // 当前是 PENDING_DISPATCH, 不在 EXCEPTION 状态
        order.setStatus(DispatchConstants.DO_PENDING_DISPATCH);

        ResolveExceptionRequest req = new ResolveExceptionRequest();
        req.setStrategy(ResolveStrategy.RE_DISPATCH);
        req.setVehicleId(200L);
        req.setDriverId(300L);

        assertThatThrownBy(() -> service.resolveException(100L, req))
                .isInstanceOf(IllegalDeliveryOrderStateException.class)
                .hasMessageContaining("PENDING_DISPATCH");

        verify(stateMachineEngine, never()).assertTransition(any(), any(), any());
        verify(orderMapper, never()).updateById(any(WmsDeliveryOrder.class));
    }
}
