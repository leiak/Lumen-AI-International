package com.lumen.integration;

import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.dto.DeliveryOrderDtos.AssignRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.CancelRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.CreateDeliveryOrderRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.LoadRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.RaiseExceptionRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.ResolveExceptionRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.ResolveStrategy;
import com.lumen.dispatch.entity.WmsDeliveryException;
import com.lumen.dispatch.entity.WmsDeliveryOrder;
import com.lumen.dispatch.entity.WmsDriver;
import com.lumen.dispatch.entity.WmsVehicle;
import com.lumen.dispatch.exception.IllegalDeliveryOrderStateException;
import com.lumen.dispatch.service.DeliveryExceptionService;
import com.lumen.dispatch.service.DeliveryOrderService;
import com.lumen.dispatch.service.DriverService;
import com.lumen.dispatch.service.VehicleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * resolveException 集成测试 (Iteration 4.1.x PR1).
 *
 * <p>覆盖 3 个场景:
 * <ul>
 *   <li>{@code RE_DISPATCH} — 走 V23 EXCEPTION → DISPATCHED (reDispatch) +
 *       VEHICLE SM IDLE → DISPATCHED.</li>
 *   <li>{@code CANCEL} — 走 V23 EXCEPTION → CANCELLED (exceptionCancel),
 *       reason 拼到 remark.</li>
 *   <li>非 EXCEPTION 状态直接调 resolveException → {@link IllegalDeliveryOrderStateException}.</li>
 * </ul>
 *
 * <p>直接调 service 层（与 {@link DeliveryFlowIT} 一致）, 不走 HTTP 端点
 * —— 状态机迁移的逻辑完整覆盖了 controller 端点的 @PreAuthorize 之外的所有
 * 行为，HTTP 层用 admin 端到端 IT (不在本切片范围) 验证.
 */
@DisplayName("resolveException 集成测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ResolveExceptionIT extends IntegrationBase {

    @Autowired private DeliveryOrderService deliveryOrderService;
    @Autowired private DeliveryExceptionService deliveryExceptionService;
    @Autowired private VehicleService vehicleService;
    @Autowired private DriverService driverService;

    private WmsVehicle initialVehicle;
    private WmsDriver initialDriver;
    private WmsVehicle spareVehicle;
    private WmsDriver spareDriver;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L);

        // 初始车 + 司机 (用来首派, 然后被异常卡住)
        com.lumen.dispatch.dto.DriverDtos.CreateDriverRequest dReq0 =
                new Helper().newDriver("138" + System.nanoTime() % 100_000_000L);
        initialDriver = driverService.create(dReq0);
        com.lumen.dispatch.dto.VehicleDtos.CreateVehicleRequest vReq0 =
                new Helper().newVehicle("VEH_R0", "LARGE");
        vReq0.setDriverId(initialDriver.getId());
        initialVehicle = vehicleService.create(vReq0);

        // 备车 + 司机 (reDispatch 时换上去)
        com.lumen.dispatch.dto.DriverDtos.CreateDriverRequest dReq1 =
                new Helper().newDriver("139" + System.nanoTime() % 100_000_000L);
        spareDriver = driverService.create(dReq1);
        com.lumen.dispatch.dto.VehicleDtos.CreateVehicleRequest vReq1 =
                new Helper().newVehicle("VEH_RA", "LARGE");
        vReq1.setDriverId(spareDriver.getId());
        spareVehicle = vehicleService.create(vReq1);
    }

    @AfterEach
    void tearDown() {
        try { if (initialVehicle != null) vehicleService.delete(initialVehicle.getId()); } catch (Exception ignored) {}
        try { if (initialDriver != null) driverService.delete(initialDriver.getId()); } catch (Exception ignored) {}
        try { if (spareVehicle != null) vehicleService.delete(spareVehicle.getId()); } catch (Exception ignored) {}
        try { if (spareDriver != null) driverService.delete(spareDriver.getId()); } catch (Exception ignored) {}
        TenantContext.clear();
    }

    // ====================================================================
    // RE_DISPATCH — 走 V23 EXCEPTION → DISPATCHED
    // ====================================================================

    @Test
    @Order(1)
    @DisplayName("resolveException_reDispatch_restoresDispatched: EXCEPTION → DISPATCHED + 备车 IDLE → DISPATCHED")
    void resolveException_reDispatch_restoresDispatched() {
        // 1. 建单 + assign (initialVehicle) + load (推到 LOADED, 满足 raiseException 前置条件)
        WmsDeliveryOrder order = createOrderInLoadedState();

        // 2. raiseException → EXCEPTION
        RaiseExceptionRequest raiseReq = new RaiseExceptionRequest();
        raiseReq.setType("REJECTED");
        raiseReq.setDescription("客户拒收, 需重新派车");
        WmsDeliveryException ex = deliveryOrderService.raiseException(order.getId(), raiseReq);
        assertThat(ex.getId()).isNotNull();
        WmsDeliveryOrder exceptioned = deliveryOrderService.get(order.getId());
        assertThat(exceptioned.getStatus()).isEqualTo(DispatchConstants.DO_EXCEPTION);
        Long oldVehicleId = exceptioned.getVehicleId();
        Long oldDriverId = exceptioned.getDriverId();
        assertThat(oldVehicleId).isEqualTo(initialVehicle.getId());

        // 3. resolveException(RE_DISPATCH, 备车)
        ResolveExceptionRequest req = new ResolveExceptionRequest();
        req.setStrategy(ResolveStrategy.RE_DISPATCH);
        req.setVehicleId(spareVehicle.getId());
        req.setDriverId(spareDriver.getId());
        req.setReason("客户拒收, 调度员换车重新派送");

        WmsDeliveryOrder resolved = deliveryOrderService.resolveException(order.getId(), req);

        // 4. 断言订单: 状态=DISPATCHED, 换绑到备车/备司机, exception_id 保留 (可追溯已 CLOSED 工单)
        assertThat(resolved.getStatus()).isEqualTo(DispatchConstants.DO_DISPATCHED);
        assertThat(resolved.getVehicleId()).isEqualTo(spareVehicle.getId());
        assertThat(resolved.getVehicleId()).isNotEqualTo(oldVehicleId);
        assertThat(resolved.getDriverId()).isEqualTo(spareDriver.getId());
        assertThat(resolved.getDriverId()).isNotEqualTo(oldDriverId);
        assertThat(resolved.getExceptionId()).isEqualTo(ex.getId());
        assertThat(resolved.getRemark()).contains("RE_DISPATCHED:");

        // 5. 断言备车: IDLE → DISPATCHED
        WmsVehicle reloadedSpare = vehicleService.get(spareVehicle.getId());
        assertThat(reloadedSpare.getStatus()).isEqualTo(DispatchConstants.VEH_DISPATCHED);
    }

    // ====================================================================
    // CANCEL — 走 V23 EXCEPTION → CANCELLED
    // ====================================================================

    @Test
    @Order(2)
    @DisplayName("resolveException_cancel_endsAsCancelled: EXCEPTION → CANCELLED + reason 拼到 remark")
    void resolveException_cancel_endsAsCancelled() {
        // 1. 建单 + raiseException
        WmsDeliveryOrder order = createOrderInLoadedState();
        RaiseExceptionRequest raiseReq = new RaiseExceptionRequest();
        raiseReq.setType("DAMAGE");
        raiseReq.setDescription("货损严重, 客户要求终止");
        deliveryOrderService.raiseException(order.getId(), raiseReq);
        WmsDeliveryOrder exceptioned = deliveryOrderService.get(order.getId());
        assertThat(exceptioned.getStatus()).isEqualTo(DispatchConstants.DO_EXCEPTION);

        // 2. resolveException(CANCEL)
        ResolveExceptionRequest req = new ResolveExceptionRequest();
        req.setStrategy(ResolveStrategy.CANCEL);
        req.setReason("货损无法修复, 客户已退款, 关单");

        WmsDeliveryOrder resolved = deliveryOrderService.resolveException(order.getId(), req);

        // 3. 断言订单状态 = CANCELLED, reason 已拼到 remark
        assertThat(resolved.getStatus()).isEqualTo(DispatchConstants.DO_CANCELLED);
        assertThat(resolved.getRemark()).contains("EXCEPTION_CANCELLED: 货损无法修复, 客户已退款, 关单");
    }

    // ====================================================================
    // 非 EXCEPTION 状态直接调 → 抛 IllegalDeliveryOrderStateException
    // ====================================================================

    @Test
    @Order(3)
    @DisplayName("resolveException_fromNonException_throws: PENDING_DISPATCH 调 → IllegalDeliveryOrderStateException")
    void resolveException_fromNonException_throws() {
        // 建单但只到 PENDING_DISPATCH, 不 raiseException
        CreateDeliveryOrderRequest createReq = new CreateDeliveryOrderRequest();
        createReq.setSourceNo("OB-IT-RE-" + System.nanoTime());
        createReq.setWarehouseId(1L);
        createReq.setTotalWeightKg(new BigDecimal("50"));
        createReq.setTotalVolumeM3(new BigDecimal("1"));
        createReq.setStopCount(1);
        WmsDeliveryOrder order = deliveryOrderService.create(createReq);
        assertThat(order.getStatus()).isEqualTo(DispatchConstants.DO_PENDING_DISPATCH);

        ResolveExceptionRequest req = new ResolveExceptionRequest();
        req.setStrategy(ResolveStrategy.CANCEL);
        req.setReason("试着关闭");

        assertThatThrownBy(() -> deliveryOrderService.resolveException(order.getId(), req))
                .isInstanceOf(IllegalDeliveryOrderStateException.class)
                .hasMessageContaining("PENDING_DISPATCH");

        // 订单状态应保持 PENDING_DISPATCH (回滚)
        WmsDeliveryOrder reloaded = deliveryOrderService.get(order.getId());
        assertThat(reloaded.getStatus()).isEqualTo(DispatchConstants.DO_PENDING_DISPATCH);
    }

    // ================================================================
    // helpers
    // ================================================================

    /**
     * 建单 + assign (initialVehicle) + load, 把订单推到 LOADED 状态 (raiseException 的前置).
     * 与 setUp() 配合 — 初始车 (initialVehicle) 被 assign 后状态变 DISPATCHED,
     * 备车 (spareVehicle) 保持 IDLE 供 reDispatch 路径切换.
     */
    private WmsDeliveryOrder createOrderInLoadedState() {
        CreateDeliveryOrderRequest createReq = new CreateDeliveryOrderRequest();
        createReq.setSourceNo("OB-IT-RA-" + System.nanoTime());
        createReq.setWarehouseId(1L);
        createReq.setTotalWeightKg(new BigDecimal("100"));
        createReq.setTotalVolumeM3(new BigDecimal("2"));
        createReq.setStopCount(1);
        WmsDeliveryOrder order = deliveryOrderService.create(createReq);

        // assign: 拿 initialVehicle 派车
        AssignRequest assignReq = new AssignRequest();
        assignReq.setVehicleId(initialVehicle.getId());
        assignReq.setDriverId(initialDriver.getId());
        deliveryOrderService.assign(order.getId(), assignReq);

        // load: DISPATCHED → LOADED (这是 raiseException 的合法前置状态)
        deliveryOrderService.load(order.getId(), new LoadRequest());

        return order;
    }

    /**
     * 静态 helper, 避免每个 IT case 都 import masterdata DTO.
     */
    static class Helper {
        com.lumen.dispatch.dto.DriverDtos.CreateDriverRequest newDriver(String phone) {
            com.lumen.dispatch.dto.DriverDtos.CreateDriverRequest d = new com.lumen.dispatch.dto.DriverDtos.CreateDriverRequest();
            d.setName("RE-Drv-" + System.nanoTime() % 100000);
            d.setPhone(phone);
            d.setLicenseNo("RE-LIC-" + System.nanoTime() % 100000000);
            return d;
        }
        com.lumen.dispatch.dto.VehicleDtos.CreateVehicleRequest newVehicle(String prefix, String type) {
            com.lumen.dispatch.dto.VehicleDtos.CreateVehicleRequest v = new com.lumen.dispatch.dto.VehicleDtos.CreateVehicleRequest();
            v.setPlateNo(prefix + "-" + System.nanoTime() % 100000000L);
            v.setType(type);
            v.setMaxLoadKg(new BigDecimal("5000"));
            v.setMaxVolumeM3(new BigDecimal("50"));
            return v;
        }
    }
}
