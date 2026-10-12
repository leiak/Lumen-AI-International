package com.lumen.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.dto.DeliveryOrderDtos.ArriveRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.AssignRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.CreateDeliveryOrderRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.DepartRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.LoadRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.RaiseExceptionRequest;
import com.lumen.dispatch.dto.DeliveryOrderDtos.SignRequest;
import com.lumen.dispatch.entity.WmsDeliveryException;
import com.lumen.dispatch.entity.WmsDeliveryOrder;
import com.lumen.dispatch.entity.WmsDeliveryStop;
import com.lumen.dispatch.entity.WmsDriver;
import com.lumen.dispatch.entity.WmsVehicle;
import com.lumen.dispatch.mapper.WmsDeliveryStopMapper;
import com.lumen.dispatch.service.DeliveryOrderService;
import com.lumen.dispatch.service.DriverService;
import com.lumen.dispatch.service.VehicleService;
import com.lumen.extension.outbox.EventOutbox;
import com.lumen.extension.outbox.EventOutboxMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配送状态机迁移 → Outbox 事件端到端 IT (Iteration 4.1.x PR2).
 *
 * <p>覆盖 2 个场景:
 * <ul>
 *   <li>{@code fullFlow_sign_creates6DeliveryOrderOutboxRows} —
 *       create + assign + load + depart + arrive + sign 产生 6 行
 *       {@code delivery_order.state_changed} (create 把 null → PENDING_DISPATCH
 *       也算一次"初始迁移"事件, 与其余 5 个状态机迁移凑齐 6 个 delivery_order 事件).
 *       0 行 {@code delivery_exception.state_changed}.</li>
 *   <li>{@code raiseException_createsExceptionAndOrderOutboxRows} —
 *       create + assign + load + raise 产生 4 行 delivery_order 事件
 *       (create + assign + load + raiseException) + 1 行 delivery_exception
 *       事件 (异常工单 OPEN 状态初创, 走 raiseException 路径同步创建).</li>
 * </ul>
 *
 * <p>事务安全: {@code DeliveryOrderServiceImpl.assign(...)} 等方法标了
 * {@code @Transactional}; {@code DefaultEventBus.publish(...)} 标了
 * {@code @Transactional(MANDATORY)}, 共用外层事务 —— 业务表回滚则 outbox 行
 * 也回滚, 不会"发了事件但状态没改".
 *
 * <p>查询方式: 直接查 {@code t_event_outbox}, 用 {@code aggregate_id}
 * (本测试是 {@code String.valueOf(orderId)}) 隔离, 不受其它 IT 留下的行干扰.
 */
@DisplayName("Outbox 配送事件 IT (4.1.x PR2)")
class OutboxDeliveryEventIT extends IntegrationBase {

    @Autowired private DeliveryOrderService deliveryOrderService;
    @Autowired private VehicleService vehicleService;
    @Autowired private DriverService driverService;
    @Autowired private WmsDeliveryStopMapper stopMapper;
    @Autowired private EventOutboxMapper outboxMapper;

    private WmsVehicle vehicle;
    private WmsDriver driver;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L);

        com.lumen.dispatch.dto.DriverDtos.CreateDriverRequest dReq =
                new dtoHelper().newDriver("138" + System.nanoTime() % 100_000_000L);
        driver = driverService.create(dReq);

        com.lumen.dispatch.dto.VehicleDtos.CreateVehicleRequest vReq =
                new dtoHelper().newVehicle("VEH_IT_OB", "MEDIUM");
        vReq.setDriverId(driver.getId());
        vehicle = vehicleService.create(vReq);
    }

    @AfterEach
    void tearDown() {
        try { if (vehicle != null) vehicleService.delete(vehicle.getId()); } catch (Exception ignored) {}
        try { if (driver != null) driverService.delete(driver.getId()); } catch (Exception ignored) {}
        TenantContext.clear();
    }

    // ================================================================
    // Case 1: 完整 6 步 → 6 行 delivery_order.state_changed
    // ================================================================

    @Test
    @DisplayName("fullFlow_sign_creates6DeliveryOrderOutboxRows: create + 5 transitions = 6 DO 事件 + 0 EX")
    void fullFlow_sign_creates6DeliveryOrderOutboxRows() {
        // 1. create (PENDING_DISPATCH) — 第 1 个 DO 事件
        WmsDeliveryOrder order = createOrder();

        // 2. assign → DISPATCHED — 第 2 个 DO 事件
        AssignRequest assignReq = new AssignRequest();
        assignReq.setVehicleId(vehicle.getId());
        assignReq.setDriverId(driver.getId());
        deliveryOrderService.assign(order.getId(), assignReq);

        // 3. load → LOADED — 第 3 个 DO 事件
        deliveryOrderService.load(order.getId(), new LoadRequest());

        // 4. depart → ENROUTE — 第 4 个 DO 事件
        deliveryOrderService.depart(order.getId(), new DepartRequest());

        // 5. arrive → ARRIVED — 第 5 个 DO 事件
        WmsDeliveryStop stop = seedStop(order.getId());
        ArriveRequest arriveReq = new ArriveRequest();
        arriveReq.setStopId(stop.getId());
        deliveryOrderService.arrive(order.getId(), arriveReq);

        // 6. sign → SIGNED — 第 6 个 DO 事件
        SignRequest signReq = new SignRequest();
        signReq.setStopId(stop.getId());
        signReq.setSignPhotoUrl("https://example.com/sign/pr2-" + System.nanoTime() + ".jpg");
        deliveryOrderService.sign(order.getId(), signReq);

        // === 断言: 6 个 DO 事件 + 0 个 EX 事件 (按 aggregate_id 隔离, 不受其他 IT 干扰) ===
        String aggId = String.valueOf(order.getId());

        List<EventOutbox> doRows = outboxMapper.selectList(
                new LambdaQueryWrapper<EventOutbox>()
                        .eq(EventOutbox::getAggregateType,
                                DispatchConstants.AGGREGATE_TYPE_DELIVERY_ORDER)
                        .eq(EventOutbox::getAggregateId, aggId)
                        .orderByAsc(EventOutbox::getId)
        );
        assertThat(doRows)
                .as("full flow should produce 6 delivery_order.state_changed rows for order %s", aggId)
                .hasSize(6);
        // 第 6 行 toState=SIGNED (按 id 升序即按事件发生顺序)
        assertThat(doRows.get(5).getEventType())
                .isEqualTo(DispatchConstants.EVENT_TYPE_DELIVERY_ORDER_STATE_CHANGED);
        assertThat(doRows.get(5).getPayload())
                .as("payload of the 6th event should mention SIGNED")
                .contains("\"toState\": \"SIGNED\"");

        // EX 事件数 = 0 (full flow 没触发异常工单)
        Long exCount = outboxMapper.selectCount(
                new LambdaQueryWrapper<EventOutbox>()
                        .eq(EventOutbox::getAggregateType,
                                DispatchConstants.AGGREGATE_TYPE_DELIVERY_EXCEPTION)
                        .eq(EventOutbox::getAggregateId, aggId)
        );
        assertThat(exCount).isZero();
    }

    // ================================================================
    // Case 2: raiseException → 4 个 DO 事件 + 1 个 EX 事件
    // ================================================================

    @Test
    @DisplayName("raiseException_createsExceptionAndOrderOutboxRows: 4 DO 事件 + 1 EX 事件")
    void raiseException_createsExceptionAndOrderOutboxRows() {
        // create + assign + load (3 个 DO 事件) — 把订单推到 LOADED 满足 raiseException 前置
        WmsDeliveryOrder order = createOrder();
        AssignRequest assignReq = new AssignRequest();
        assignReq.setVehicleId(vehicle.getId());
        assignReq.setDriverId(driver.getId());
        deliveryOrderService.assign(order.getId(), assignReq);
        deliveryOrderService.load(order.getId(), new LoadRequest());

        // raiseException → 1 个 DO 事件 (LOADED → EXCEPTION) + 1 个 EX 事件 (OPEN)
        RaiseExceptionRequest raiseReq = new RaiseExceptionRequest();
        raiseReq.setType("REJECTED");
        raiseReq.setDescription("客户拒收, 触发 PR2 outbox 验证");
        WmsDeliveryException ex = deliveryOrderService.raiseException(order.getId(), raiseReq);

        // === 断言: 4 个 DO 事件 ===
        List<EventOutbox> doRows = outboxMapper.selectList(
                new LambdaQueryWrapper<EventOutbox>()
                        .eq(EventOutbox::getAggregateType,
                                DispatchConstants.AGGREGATE_TYPE_DELIVERY_ORDER)
                        .eq(EventOutbox::getAggregateId, String.valueOf(order.getId()))
                        .orderByAsc(EventOutbox::getId)
        );
        assertThat(doRows)
                .as("create + assign + load + raiseException = 4 DO rows for order %s", order.getId())
                .hasSize(4);
        // 第 4 行 (raiseException): toState=EXCEPTION, action=raiseException
        assertThat(doRows.get(3).getPayload())
                .as("4th DO event payload should be the raiseException transition")
                .contains("\"toState\": \"EXCEPTION\"")
                .contains("\"action\": \"raiseException\"");

        // === 断言: 1 个 EX 事件 (aggregate_id = exceptionId) ===
        List<EventOutbox> exRows = outboxMapper.selectList(
                new LambdaQueryWrapper<EventOutbox>()
                        .eq(EventOutbox::getAggregateType,
                                DispatchConstants.AGGREGATE_TYPE_DELIVERY_EXCEPTION)
                        .eq(EventOutbox::getAggregateId, String.valueOf(ex.getId()))
        );
        assertThat(exRows)
                .as("raiseException creates 1 EX row for exception %s", ex.getId())
                .hasSize(1);
        assertThat(exRows.get(0).getEventType())
                .isEqualTo(DispatchConstants.EVENT_TYPE_DELIVERY_EXCEPTION_STATE_CHANGED);
        assertThat(exRows.get(0).getPayload())
                .as("EX event payload should have toState=OPEN")
                .contains("\"toState\": \"OPEN\"")
                .contains("\"action\": \"create\"");
    }

    // ================================================================
    // helpers
    // ================================================================

    private WmsDeliveryOrder createOrder() {
        CreateDeliveryOrderRequest req = new CreateDeliveryOrderRequest();
        req.setSourceNo("OB-IT-PR2-" + System.nanoTime());
        req.setWarehouseId(1L);
        req.setTotalWeightKg(new BigDecimal("100"));
        req.setTotalVolumeM3(new BigDecimal("2"));
        req.setStopCount(1);
        return deliveryOrderService.create(req);
    }

    private WmsDeliveryStop seedStop(Long orderId) {
        WmsDeliveryStop stop = new WmsDeliveryStop();
        stop.setTenantId(1L);
        stop.setOrderId(orderId);
        stop.setStopNo(1);
        stop.setAddress("客户地址 (PR2 IT)");
        stop.setContactName("客户");
        stop.setContactPhone("13800000000");
        stop.setStatus("PENDING");
        stopMapper.insert(stop);
        return stop;
    }

    static class dtoHelper {
        com.lumen.dispatch.dto.DriverDtos.CreateDriverRequest newDriver(String phone) {
            com.lumen.dispatch.dto.DriverDtos.CreateDriverRequest d =
                    new com.lumen.dispatch.dto.DriverDtos.CreateDriverRequest();
            d.setName("PR2-Drv-" + System.nanoTime() % 100000);
            d.setPhone(phone);
            d.setLicenseNo("PR2-LIC-" + System.nanoTime() % 100000000);
            return d;
        }
        com.lumen.dispatch.dto.VehicleDtos.CreateVehicleRequest newVehicle(String prefix, String type) {
            com.lumen.dispatch.dto.VehicleDtos.CreateVehicleRequest v =
                    new com.lumen.dispatch.dto.VehicleDtos.CreateVehicleRequest();
            v.setPlateNo(prefix + "-" + System.nanoTime() % 100000000L);
            v.setType(type);
            v.setMaxLoadKg(new BigDecimal("1000"));
            v.setMaxVolumeM3(new BigDecimal("10"));
            return v;
        }
    }
}