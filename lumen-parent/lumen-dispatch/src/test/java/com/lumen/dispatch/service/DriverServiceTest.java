package com.lumen.dispatch.service;

import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.dto.DriverDtos.CreateDriverRequest;
import com.lumen.dispatch.dto.DriverDtos.UpdateDriverRequest;
import com.lumen.dispatch.entity.WmsDriver;
import com.lumen.dispatch.exception.DriverDuplicatePhoneException;
import com.lumen.dispatch.exception.DriverNotFoundException;
import com.lumen.dispatch.mapper.WmsDriverMapper;
import com.lumen.dispatch.service.impl.DriverServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DriverService 单元测试 (Iteration 4.1 PR3).
 *
 * <p>司机主数据状态机极简（DB CHECK ACTIVE/SUSPENDED 二选一），不接入
 * {@code t_state_transition}，所以 suspend / resume 直接 setStatus.
 *
 * <p>覆盖：
 * <ul>
 *   <li>create：默认 ACTIVE 回填 + phone 重复抛 {@link DriverDuplicatePhoneException}</li>
 *   <li>get：id 不存在抛 NotFound</li>
 *   <li>suspend：ACTIVE → SUSPENDED</li>
 *   <li>resume：SUSPENDED → ACTIVE</li>
 *   <li>delete：id 不存在抛 NotFound</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DriverService 单元测试")
class DriverServiceTest {

    @Mock private WmsDriverMapper mapper;

    @InjectMocks private DriverServiceImpl service;

    private WmsDriver existing;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L);
        existing = new WmsDriver();
        existing.setId(200L);
        existing.setTenantId(1L);
        existing.setName("张师傅");
        existing.setPhone("13800000001");
        existing.setLicenseNo("BJ-DRV-001");
        existing.setStatus("ACTIVE");
        lenient().when(mapper.selectById(200L)).thenReturn(existing);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("create: 默认 status=ACTIVE + tenant 回填")
    void create_defaultsToActive() {
        CreateDriverRequest req = new CreateDriverRequest();
        req.setName("李师傅");
        req.setPhone("13800000099");
        req.setLicenseNo("BJ-DRV-099");

        when(mapper.insert(any(WmsDriver.class))).thenAnswer(inv -> {
            WmsDriver d = inv.getArgument(0);
            d.setId(1L);
            return 1;
        });

        WmsDriver result = service.create(req);

        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.getTenantId()).isEqualTo(1L);
        verify(mapper).insert(any(WmsDriver.class));
    }

    @Test
    @DisplayName("create: 手机号重复 → DriverDuplicatePhoneException")
    void create_duplicatePhone() {
        CreateDriverRequest req = new CreateDriverRequest();
        req.setName("王师傅");
        req.setPhone("13800000001");

        when(mapper.insert(any(WmsDriver.class)))
                .thenThrow(new DuplicateKeyException("uk_wms_driver_tenant_phone"));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(DriverDuplicatePhoneException.class)
                .hasMessageContaining("13800000001");
    }

    @Test
    @DisplayName("get: id 不存在 → DriverNotFoundException")
    void get_notFound() {
        when(mapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.get(99L))
                .isInstanceOf(DriverNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("suspend: ACTIVE → SUSPENDED，updateById 触发")
    void suspend_activeToSuspended() {
        existing.setStatus("ACTIVE");
        when(mapper.selectById(200L)).thenReturn(existing);

        WmsDriver result = service.suspend(200L);

        assertThat(result.getStatus()).isEqualTo("SUSPENDED");
        verify(mapper).updateById(existing);
    }

    @Test
    @DisplayName("resume: SUSPENDED → ACTIVE")
    void resume_suspendedToActive() {
        existing.setStatus("SUSPENDED");
        when(mapper.selectById(200L)).thenReturn(existing);

        WmsDriver result = service.resume(200L);

        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        verify(mapper).updateById(existing);
    }

    @Test
    @DisplayName("delete: id 不存在 → DriverNotFoundException")
    void delete_notFound() {
        when(mapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(DriverNotFoundException.class);
    }
}