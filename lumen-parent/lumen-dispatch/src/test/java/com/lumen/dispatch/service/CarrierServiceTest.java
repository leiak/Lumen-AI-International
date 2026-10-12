package com.lumen.dispatch.service;

import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.dto.CarrierDtos.CreateCarrierRequest;
import com.lumen.dispatch.dto.CarrierDtos.UpdateCarrierRequest;
import com.lumen.dispatch.entity.WmsCarrier;
import com.lumen.dispatch.exception.CarrierDuplicateCodeException;
import com.lumen.dispatch.exception.CarrierNotFoundException;
import com.lumen.dispatch.mapper.WmsCarrierMapper;
import com.lumen.dispatch.service.impl.CarrierServiceImpl;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CarrierService 单元测试 (Iteration 4.1 PR3).
 *
 * <p>承运人不参与状态机，覆盖 CRUD + 唯一约束：
 * <ul>
 *   <li>create：默认 ACTIVE 回填 + code 重复抛 {@link CarrierDuplicateCodeException}</li>
 *   <li>get / delete：id 不存在时抛 NotFound</li>
 *   <li>update：code 冲突抛 DuplicateCodeException</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CarrierService 单元测试")
class CarrierServiceTest {

    @Mock private WmsCarrierMapper mapper;

    @InjectMocks private CarrierServiceImpl service;

    private WmsCarrier existing;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L);
        existing = new WmsCarrier();
        existing.setId(300L);
        existing.setTenantId(1L);
        existing.setCode("SF");
        existing.setName("顺丰物流");
        existing.setContact("4008-111-111");
        existing.setStatus("ACTIVE");
        lenient().when(mapper.selectById(300L)).thenReturn(existing);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("create: 默认 status=ACTIVE + tenant 回填")
    void create_defaultsToActive() {
        CreateCarrierRequest req = new CreateCarrierRequest();
        req.setCode("JD");
        req.setName("京东物流");
        req.setContact("4008-333-333");

        when(mapper.insert(any(WmsCarrier.class))).thenAnswer(inv -> {
            WmsCarrier c = inv.getArgument(0);
            c.setId(1L);
            return 1;
        });

        WmsCarrier result = service.create(req);

        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.getTenantId()).isEqualTo(1L);
        verify(mapper).insert(any(WmsCarrier.class));
    }

    @Test
    @DisplayName("create: 编码重复 → CarrierDuplicateCodeException")
    void create_duplicateCode() {
        CreateCarrierRequest req = new CreateCarrierRequest();
        req.setCode("SF");
        req.setName("顺丰");

        when(mapper.insert(any(WmsCarrier.class)))
                .thenThrow(new DuplicateKeyException("uk_wms_carrier_tenant_code"));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(CarrierDuplicateCodeException.class)
                .hasMessageContaining("SF");
    }

    @Test
    @DisplayName("get: id 不存在 → CarrierNotFoundException")
    void get_notFound() {
        when(mapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.get(99L))
                .isInstanceOf(CarrierNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("update: 编码冲突 → CarrierDuplicateCodeException")
    void update_duplicateCode() {
        UpdateCarrierRequest req = new UpdateCarrierRequest();
        req.setCode("YT");

        when(mapper.updateById(any(WmsCarrier.class)))
                .thenThrow(new DuplicateKeyException("uk_wms_carrier_tenant_code"));

        assertThatThrownBy(() -> service.update(300L, req))
                .isInstanceOf(CarrierDuplicateCodeException.class)
                .hasMessageContaining("YT");
    }

    @Test
    @DisplayName("delete: id 不存在 → CarrierNotFoundException，mapper.deleteById 不调用")
    void delete_notFound() {
        when(mapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(CarrierNotFoundException.class);
        verify(mapper, never()).deleteById(any(Long.class));
    }
}