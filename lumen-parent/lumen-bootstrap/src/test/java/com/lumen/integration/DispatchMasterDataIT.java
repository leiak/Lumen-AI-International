package com.lumen.integration;

import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.dto.CarrierDtos;
import com.lumen.dispatch.dto.DriverDtos;
import com.lumen.dispatch.dto.VehicleDtos;
import com.lumen.dispatch.entity.WmsCarrier;
import com.lumen.dispatch.entity.WmsDriver;
import com.lumen.dispatch.entity.WmsVehicle;
import com.lumen.dispatch.exception.CarrierDuplicateCodeException;
import com.lumen.dispatch.exception.CarrierNotFoundException;
import com.lumen.dispatch.exception.DriverDuplicatePhoneException;
import com.lumen.dispatch.exception.DriverNotFoundException;
import com.lumen.dispatch.exception.VehicleDuplicatePlateException;
import com.lumen.dispatch.exception.VehicleNotFoundException;
import com.lumen.dispatch.service.CarrierService;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dispatch masterdata 集成测试 (Iteration 4.1 PR3).
 *
 * <p>覆盖：
 * <ul>
 *   <li>vehicle 完整 CRUD + 状态机迁移 (IDLE → freeze → OUT → IDLE)</li>
 *   <li>vehicleDuplicatePlate: 同一租户第二次同号抛 409</li>
 *   <li>driver CRUD + suspend/resume (ACTIVE/SUSPENDED 二状态机)</li>
 *   <li>driverDuplicatePhone: 同一租户第二次同号抛 409</li>
 *   <li>carrier CRUD + 编码重复 409</li>
 *   <li>fleetMasterDataIT_fullFlow: vehicle + driver + carrier 跨实体全流程</li>
 * </ul>
 *
 * <p>复用 {@link IntegrationBase} 提供的 testcontainers + MyBatis-Plus
 * 多租户拦截器；测试租户固定为 1L（dev 演示 tenant），并通过
 * {@link #unique(String)} 追加 nanoTime 后缀避免与 V22 seed 数据 / 跨测试唯一约束冲突.
 */
@DisplayName("Dispatch 主数据集成测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DispatchMasterDataIT extends IntegrationBase {

    private static final AtomicLong SEQ = new AtomicLong(0);

    @Autowired private VehicleService vehicleService;
    @Autowired private DriverService driverService;
    @Autowired private CarrierService carrierService;

    @BeforeEach
    void setUpTenant() {
        TenantContext.set(1L);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ====================================================================
    // Vehicle — CRUD + freeze / unfreeze
    // ====================================================================

    @Test
    @Order(1)
    @DisplayName("vehicleCRUD_tenantIsolation: create + get + update + page + delete")
    void vehicleCRUD_tenantIsolation() {
        VehicleDtos.CreateVehicleRequest req = newVehicle("VEH_CRUD", "SMALL");
        WmsVehicle created = vehicleService.create(req);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getPlateNo()).isEqualTo(req.getPlateNo());
        assertThat(created.getStatus()).isEqualTo(DispatchConstants.VEH_IDLE);
        assertThat(created.getTenantId()).isEqualTo(1L);

        // get
        WmsVehicle fetched = vehicleService.get(created.getId());
        assertThat(fetched.getPlateNo()).isEqualTo(req.getPlateNo());

        // update
        VehicleDtos.UpdateVehicleRequest upd = new VehicleDtos.UpdateVehicleRequest();
        upd.setRemark("updated remark");
        WmsVehicle updated = vehicleService.update(created.getId(), upd);
        assertThat(updated.getRemark()).isEqualTo("updated remark");

        // page includes this plate
        PageResult<WmsVehicle> page = vehicleService.page(
                1L, 50L, VehicleDtos.QueryVehicleRequest.from(req.getType(), null, req.getPlateNo()));
        assertThat(page.getRecords()).extracting(WmsVehicle::getId)
                .contains(created.getId());

        // delete
        vehicleService.delete(created.getId());
        assertThatThrownBy(() -> vehicleService.get(created.getId()))
                .isInstanceOf(VehicleNotFoundException.class);
    }

    @Test
    @Order(2)
    @DisplayName("vehicleFreeze_statusChanges: IDLE → freeze → OUT → unfreeze → IDLE")
    void vehicleFreeze_statusChanges() {
        VehicleDtos.CreateVehicleRequest req = newVehicle("VEH_FZ", "MEDIUM");
        WmsVehicle created = vehicleService.create(req);
        assertThat(created.getStatus()).isEqualTo(DispatchConstants.VEH_IDLE);

        // freeze → OUT
        WmsVehicle frozen = vehicleService.freeze(created.getId());
        assertThat(frozen.getStatus()).isEqualTo(DispatchConstants.VEH_OUT);

        // unfreeze → IDLE
        WmsVehicle unfrozen = vehicleService.unfreeze(created.getId());
        assertThat(unfrozen.getStatus()).isEqualTo(DispatchConstants.VEH_IDLE);

        // clean up
        vehicleService.delete(created.getId());
    }

    @Test
    @Order(3)
    @DisplayName("vehicleDuplicatePlate_throws409: 同租户二次创建同 plate 抛 VehicleDuplicatePlateException")
    void vehicleDuplicatePlate_throws409() {
        String dupPlate = "VEH_DUP_" + System.nanoTime();
        VehicleDtos.CreateVehicleRequest first = newVehicle("VEH_DUP", "SMALL");
        first.setPlateNo(dupPlate);
        WmsVehicle created = vehicleService.create(first);

        VehicleDtos.CreateVehicleRequest second = newVehicle("VEH_DUP", "SMALL");
        second.setPlateNo(dupPlate);
        assertThatThrownBy(() -> vehicleService.create(second))
                .isInstanceOf(VehicleDuplicatePlateException.class)
                .hasMessageContaining(dupPlate);

        vehicleService.delete(created.getId());
    }

    // ====================================================================
    // Driver — CRUD + suspend / resume
    // ====================================================================

    @Test
    @Order(4)
    @DisplayName("driverCRUD_suspendResume: create + suspend + resume")
    void driverCRUD_suspendResume() {
        DriverDtos.CreateDriverRequest req = newDriver("13800009001");
        WmsDriver created = driverService.create(req);
        assertThat(created.getStatus()).isEqualTo("ACTIVE");

        // suspend
        WmsDriver suspended = driverService.suspend(created.getId());
        assertThat(suspended.getStatus()).isEqualTo("SUSPENDED");

        // resume
        WmsDriver resumed = driverService.resume(created.getId());
        assertThat(resumed.getStatus()).isEqualTo("ACTIVE");

        // update + delete
        DriverDtos.UpdateDriverRequest upd = new DriverDtos.UpdateDriverRequest();
        upd.setRemark("updated driver");
        WmsDriver updated = driverService.update(created.getId(), upd);
        assertThat(updated.getRemark()).isEqualTo("updated driver");

        driverService.delete(created.getId());
        assertThatThrownBy(() -> driverService.get(created.getId()))
                .isInstanceOf(DriverNotFoundException.class);
    }

    @Test
    @Order(5)
    @DisplayName("driverDuplicatePhone_throws409: 同租户二次创建同 phone 抛 DriverDuplicatePhoneException")
    void driverDuplicatePhone_throws409() {
        String dupPhone = "13800009" + (System.nanoTime() % 10_000_000L);
        DriverDtos.CreateDriverRequest first = newDriver(dupPhone);
        WmsDriver created = driverService.create(first);

        DriverDtos.CreateDriverRequest second = newDriver(dupPhone);
        assertThatThrownBy(() -> driverService.create(second))
                .isInstanceOf(DriverDuplicatePhoneException.class)
                .hasMessageContaining(dupPhone);

        driverService.delete(created.getId());
    }

    // ====================================================================
    // Carrier — CRUD
    // ====================================================================

    @Test
    @Order(6)
    @DisplayName("carrierCRUD_tenantIsolation: 承运人 create + get + update + delete")
    void carrierCRUD_tenantIsolation() {
        CarrierDtos.CreateCarrierRequest req = newCarrier("CR", "承运");
        WmsCarrier created = carrierService.create(req);
        assertThat(created.getStatus()).isEqualTo("ACTIVE");
        assertThat(created.getCode()).isEqualTo(req.getCode());

        WmsCarrier fetched = carrierService.get(created.getId());
        assertThat(fetched.getCode()).isEqualTo(req.getCode());

        CarrierDtos.UpdateCarrierRequest upd = new CarrierDtos.UpdateCarrierRequest();
        upd.setContact("新联系方式");
        WmsCarrier updated = carrierService.update(created.getId(), upd);
        assertThat(updated.getContact()).isEqualTo("新联系方式");

        carrierService.delete(created.getId());
        assertThatThrownBy(() -> carrierService.get(created.getId()))
                .isInstanceOf(CarrierNotFoundException.class);
    }

    @Test
    @Order(7)
    @DisplayName("carrierDuplicateCode_throws409: 同租户二次创建同 code 抛 CarrierDuplicateCodeException")
    void carrierDuplicateCode_throws409() {
        String dupCode = "CR_DUP_" + System.nanoTime();
        CarrierDtos.CreateCarrierRequest first = newCarrier("CR", "first");
        first.setCode(dupCode);
        WmsCarrier created = carrierService.create(first);

        CarrierDtos.CreateCarrierRequest second = newCarrier("CR", "second");
        second.setCode(dupCode);
        assertThatThrownBy(() -> carrierService.create(second))
                .isInstanceOf(CarrierDuplicateCodeException.class)
                .hasMessageContaining(dupCode);

        carrierService.delete(created.getId());
    }

    // ====================================================================
    // Fleet — cross-entity references
    @Test
    @Order(8)
    @DisplayName("fleetMasterDataIT_fullFlow: vehicle + driver + carrier 跨实体完整流程")
    void fleetMasterDataIT_fullFlow() {
        // 1. carrier
        CarrierDtos.CreateCarrierRequest carrierReq = newCarrier("FL", "fleetCarrier");
        WmsCarrier carrier = carrierService.create(carrierReq);

        // 2. driver
        DriverDtos.CreateDriverRequest driverReq = newDriver("13800008888");
        WmsDriver driver = driverService.create(driverReq);

        // 3. vehicle tied to driver + carrier
        VehicleDtos.CreateVehicleRequest vehicleReq = newVehicle("VEH_FLEET", "LARGE");
        vehicleReq.setDriverId(driver.getId());
        vehicleReq.setCarrierId(carrier.getId());
        WmsVehicle vehicle = vehicleService.create(vehicleReq);

        // 4. cross-reference checks
        WmsVehicle reloaded = vehicleService.get(vehicle.getId());
        assertThat(reloaded.getDriverId()).isEqualTo(driver.getId());
        assertThat(reloaded.getCarrierId()).isEqualTo(carrier.getId());

        // 5. freeze + cleanup
        vehicleService.freeze(vehicle.getId());
        WmsVehicle frozen = vehicleService.get(vehicle.getId());
        assertThat(frozen.getStatus()).isEqualTo(DispatchConstants.VEH_OUT);

        vehicleService.delete(vehicle.getId());
        driverService.delete(driver.getId());
        carrierService.delete(carrier.getId());

        assertThatThrownBy(() -> vehicleService.get(vehicle.getId()))
                .isInstanceOf(VehicleNotFoundException.class);
        assertThatThrownBy(() -> driverService.get(driver.getId()))
                .isInstanceOf(DriverNotFoundException.class);
        assertThatThrownBy(() -> carrierService.get(carrier.getId()))
                .isInstanceOf(CarrierNotFoundException.class);
    }

    // ====================================================================
    // helpers
    // ====================================================================

    /**
     * 生成不会与 V22 seed 数据（京A00001..03）/ 跨测试唯一约束冲突的车牌号.
     *
     * <p>plate_no 列 VARCHAR(32)，加 14 位后缀刚好 <= 32。
     */
    private static VehicleDtos.CreateVehicleRequest newVehicle(String prefix, String type) {
        VehicleDtos.CreateVehicleRequest req = new VehicleDtos.CreateVehicleRequest();
        req.setPlateNo(prefix + unique());
        req.setType(type);
        req.setMaxLoadKg(new BigDecimal("500.00"));
        req.setMaxVolumeM3(new BigDecimal("5.00"));
        return req;
    }

    private static DriverDtos.CreateDriverRequest newDriver(String phoneOrPrefix) {
        DriverDtos.CreateDriverRequest req = new DriverDtos.CreateDriverRequest();
        req.setName("驱-" + unique().substring(0, 6));
        req.setPhone(phoneOrPrefix);
        req.setLicenseNo("LIC-" + unique().substring(0, 8));
        return req;
    }

    private static CarrierDtos.CreateCarrierRequest newCarrier(String prefix, String name) {
        CarrierDtos.CreateCarrierRequest req = new CarrierDtos.CreateCarrierRequest();
        req.setCode(prefix + unique().substring(0, 8));
        req.setName(name + "-" + unique().substring(0, 4));
        req.setContact("4008-" + System.nanoTime() % 10000);
        return req;
    }

    private static String unique() {
        // last 9 digits of nanoTime + atomic seq → unique enough for our 32-char column
        long nanos = System.nanoTime();
        String suffix = Long.toString(nanos);
        if (suffix.length() > 9) suffix = suffix.substring(suffix.length() - 9);
        return suffix + SEQ.incrementAndGet();
    }
}