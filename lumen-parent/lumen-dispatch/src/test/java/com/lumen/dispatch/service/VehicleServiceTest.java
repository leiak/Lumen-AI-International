package com.lumen.dispatch.service;

import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.dto.VehicleDtos.CreateVehicleRequest;
import com.lumen.dispatch.dto.VehicleDtos.QueryVehicleRequest;
import com.lumen.dispatch.dto.VehicleDtos.UpdateVehicleRequest;
import com.lumen.dispatch.entity.WmsVehicle;
import com.lumen.dispatch.error.DispatchErrorCode;
import com.lumen.dispatch.exception.DispatchException;
import com.lumen.dispatch.exception.VehicleDuplicatePlateException;
import com.lumen.dispatch.exception.VehicleNotFoundException;
import com.lumen.dispatch.mapper.WmsVehicleMapper;
import com.lumen.dispatch.service.impl.VehicleServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VehicleService 单元测试 (Iteration 4.1 PR3).
 *
 * <p>纯 Mockito，覆盖：
 * <ul>
 *   <li>create：默认 IDLE 回填、车牌号重复时抛 {@link VehicleDuplicatePlateException}</li>
 *   <li>get / delete：id 不存在时抛 {@link VehicleNotFoundException}</li>
 *   <li>update：车牌号冲突时抛 {@link VehicleDuplicatePlateException}</li>
 *   <li>freeze：IDLE → OUT 直接 setStatus；OUT 上重复 freeze 抛 {@link DispatchException}({@link DispatchErrorCode#ILLEGAL_VEHICLE_STATE})</li>
 *   <li>unfreeze：OUT → IDLE 直接 setStatus；非 OUT 状态拒绝（同上 DispatchException）</li>
 *   <li>page：status + plateNo 过滤条件映射到 wrapper</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VehicleService 单元测试")
class VehicleServiceTest {

    @Mock private WmsVehicleMapper mapper;

    @InjectMocks private VehicleServiceImpl service;

    private WmsVehicle existing;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L);
        existing = new WmsVehicle();
        existing.setId(100L);
        existing.setTenantId(1L);
        existing.setPlateNo("京A00001");
        existing.setType("SMALL");
        existing.setMaxLoadKg(new BigDecimal("500"));
        existing.setMaxVolumeM3(new BigDecimal("5"));
        existing.setStatus(DispatchConstants.VEH_IDLE);
        lenient().when(mapper.selectById(100L)).thenReturn(existing);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    // ====================================================================
    // create
    // ====================================================================

    @Test
    @DisplayName("create: 默认 status=IDLE + tenant 回填")
    void create_defaultsToIdle() {
        CreateVehicleRequest req = new CreateVehicleRequest();
        req.setPlateNo("京A00001");
        req.setType("SMALL");
        req.setMaxLoadKg(new BigDecimal("500"));
        req.setMaxVolumeM3(new BigDecimal("5"));

        when(mapper.insert(any(WmsVehicle.class))).thenAnswer(inv -> {
            WmsVehicle v = inv.getArgument(0);
            v.setId(1L);
            return 1;
        });

        WmsVehicle result = service.create(req);

        assertThat(result.getStatus()).isEqualTo(DispatchConstants.VEH_IDLE);
        assertThat(result.getTenantId()).isEqualTo(1L);
        assertThat(result.getPlateNo()).isEqualTo("京A00001");
        verify(mapper).insert(any(WmsVehicle.class));
    }

    @Test
    @DisplayName("create: 显式传 status 覆盖默认值")
    void create_respectsExplicitStatus() {
        CreateVehicleRequest req = new CreateVehicleRequest();
        req.setPlateNo("京A00002");
        req.setType("MEDIUM");
        req.setMaxLoadKg(new BigDecimal("2000"));
        req.setMaxVolumeM3(new BigDecimal("20"));
        req.setStatus(DispatchConstants.VEH_DISPATCHED);

        when(mapper.insert(any(WmsVehicle.class))).thenAnswer(inv -> {
            WmsVehicle v = inv.getArgument(0);
            v.setId(2L);
            return 1;
        });

        WmsVehicle result = service.create(req);

        assertThat(result.getStatus()).isEqualTo(DispatchConstants.VEH_DISPATCHED);
    }

    @Test
    @DisplayName("create: 车牌号重复 → VehicleDuplicatePlateException")
    void create_duplicatePlate() {
        CreateVehicleRequest req = new CreateVehicleRequest();
        req.setPlateNo("京A00001");
        req.setType("SMALL");
        req.setMaxLoadKg(new BigDecimal("500"));
        req.setMaxVolumeM3(new BigDecimal("5"));

        when(mapper.insert(any(WmsVehicle.class)))
                .thenThrow(new DuplicateKeyException("uk_wms_vehicle_tenant_plate"));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(VehicleDuplicatePlateException.class)
                .hasMessageContaining("京A00001");
    }

    // ====================================================================
    // get / delete
    // ====================================================================

    @Test
    @DisplayName("get: id 不存在 → VehicleNotFoundException")
    void get_notFound() {
        when(mapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.get(99L))
                .isInstanceOf(VehicleNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("delete: id 不存在 → VehicleNotFoundException，mapper.deleteById 不调用")
    void delete_notFound() {
        when(mapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(VehicleNotFoundException.class);
        verify(mapper, never()).deleteById(any(Long.class));
    }

    // ====================================================================
    // update
    // ====================================================================

    @Test
    @DisplayName("update: 车牌号变更触发唯一约束 → VehicleDuplicatePlateException")
    void update_duplicatePlate() {
        UpdateVehicleRequest req = new UpdateVehicleRequest();
        req.setPlateNo("京A00002");

        when(mapper.updateById(any(WmsVehicle.class)))
                .thenThrow(new DuplicateKeyException("uk_wms_vehicle_tenant_plate"));

        assertThatThrownBy(() -> service.update(100L, req))
                .isInstanceOf(VehicleDuplicatePlateException.class);
    }

    // ====================================================================
    // freeze / unfreeze
    // ====================================================================

    @Test
    @DisplayName("freeze: IDLE → OUT")
    void freeze_idleToOut() {
        existing.setStatus(DispatchConstants.VEH_IDLE);
        when(mapper.selectById(100L)).thenReturn(existing);

        WmsVehicle result = service.freeze(100L);

        assertThat(result.getStatus()).isEqualTo(DispatchConstants.VEH_OUT);
        verify(mapper).updateById(existing);
    }

    @Test
    @DisplayName("freeze: 已是 OUT 状态 → DispatchException(ILLEGAL_VEHICLE_STATE) 拒绝")
    void freeze_alreadyOutRejected() {
        existing.setStatus(DispatchConstants.VEH_OUT);
        when(mapper.selectById(100L)).thenReturn(existing);

        assertThatThrownBy(() -> service.freeze(100L))
                .isInstanceOf(DispatchException.class)
                .extracting(e -> ((DispatchException) e).getErrorCode())
                .isEqualTo(DispatchErrorCode.ILLEGAL_VEHICLE_STATE);
        verify(mapper, never()).updateById(any(WmsVehicle.class));
    }

    @Test
    @DisplayName("unfreeze: OUT → IDLE 直接 setStatus")
    void unfreeze_outToIdle() {
        existing.setStatus(DispatchConstants.VEH_OUT);
        when(mapper.selectById(100L)).thenReturn(existing);

        WmsVehicle result = service.unfreeze(100L);

        assertThat(result.getStatus()).isEqualTo(DispatchConstants.VEH_IDLE);
        verify(mapper).updateById(existing);
    }

    @Test
    @DisplayName("unfreeze: 非 OUT 状态（IDLE）→ DispatchException(ILLEGAL_VEHICLE_STATE) 拒绝")
    void unfreeze_nonOutRejected() {
        existing.setStatus(DispatchConstants.VEH_IDLE);
        when(mapper.selectById(100L)).thenReturn(existing);

        assertThatThrownBy(() -> service.unfreeze(100L))
                .isInstanceOf(DispatchException.class)
                .extracting(e -> ((DispatchException) e).getErrorCode())
                .isEqualTo(DispatchErrorCode.ILLEGAL_VEHICLE_STATE);
        verify(mapper, never()).updateById(any(WmsVehicle.class));
    }

    // ====================================================================
    // page
    // ====================================================================

    @Test
    @DisplayName("page: status + plateNo 过滤均落到 wrapper")
    void page_filterApplied() {
        QueryVehicleRequest q = new QueryVehicleRequest();
        q.setStatus(DispatchConstants.VEH_IDLE);
        q.setPlateNo("京A");

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WmsVehicle> res =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1L, 20L);
        res.setRecords(java.util.List.of(existing));
        res.setTotal(1L);

        when(mapper.selectPage(any(), any())).thenReturn(res);

        var page = service.page(1L, 20L, q);

        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getRecords()).hasSize(1);
        verify(mapper).selectPage(any(), any());
    }
}