package com.lumen.cs.service;

import com.lumen.cs.dto.SlaConfigDtos.CreateRequest;
import com.lumen.cs.dto.SlaConfigDtos.GetResponse;
import com.lumen.cs.dto.SlaConfigDtos.UpdateRequest;
import com.lumen.cs.entity.SlaConfig;
import com.lumen.cs.exception.CsErrorCode;
import com.lumen.cs.exception.CsException;
import com.lumen.cs.mapper.SlaConfigMapper;
import com.lumen.cs.service.impl.SlaConfigServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SlaConfigService 单元测试 (Iteration 4.2 PR2).
 *
 * <p>覆盖：
 * <ul>
 *   <li>create: resolve_minutes &lt; response_minutes 抛 INVALID_RESOLVE_MINUTES</li>
 *   <li>create: tenant 回填 + 字段写入</li>
 *   <li>create: UNIQUE KEY 重复抛 DUPLICATE_SLA_CONFIG</li>
 *   <li>update: 校验 + 写入</li>
 *   <li>get/delete: 跨租户访问隔离</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SlaConfigService 单元测试")
class SlaConfigServiceTest {

    @Mock private SlaConfigMapper mapper;
    private SlaConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SlaConfigServiceImpl(mapper);
    }

    @AfterEach
    void tearDown() {
        // TenantContext not used here (service takes tenantId as param)
    }

    @Test
    @DisplayName("create: resolve_minutes < response_minutes → INVALID_RESOLVE_MINUTES")
    void create_resolveLessThanResponse_throws() {
        CreateRequest req = new CreateRequest("HIGH", "L1", 100, 50, null);

        assertThatThrownBy(() -> service.create(1L, req))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(CsErrorCode.INVALID_RESOLVE_MINUTES);
    }

    @Test
    @DisplayName("create: tenant 回填 + 字段写入")
    void create_persistsRow() {
        when(mapper.insert(any(SlaConfig.class))).thenAnswer(inv -> {
            SlaConfig arg = inv.getArgument(0);
            arg.setId(1L);
            return 1;
        });

        GetResponse resp = service.create(1L,
            new CreateRequest("HIGH", "L1", 30, 240, "L2"));

        ArgumentCaptor<SlaConfig> cap = ArgumentCaptor.forClass(SlaConfig.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getTenantId()).isEqualTo(1L);
        assertThat(cap.getValue().getSeverity()).isEqualTo("HIGH");
        assertThat(cap.getValue().getLevel()).isEqualTo("L1");
        assertThat(cap.getValue().getResponseMinutes()).isEqualTo(30);
        assertThat(cap.getValue().getResolveMinutes()).isEqualTo(240);
        assertThat(cap.getValue().getEscalateToLevel()).isEqualTo("L2");
        assertThat(resp.id()).isEqualTo(1L);
    }

    @Test
    @DisplayName("create: UNIQUE KEY 冲突 → DUPLICATE_SLA_CONFIG")
    void create_duplicateThrows() {
        when(mapper.insert(any(SlaConfig.class)))
            .thenThrow(new DuplicateKeyException("uk_tenant_severity_level"));

        assertThatThrownBy(() -> service.create(1L, new CreateRequest("HIGH", "L1", 30, 240, null)))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(CsErrorCode.DUPLICATE_SLA_CONFIG);
    }

    @Test
    @DisplayName("update: resolve_minutes 校验失败抛 INVALID_RESOLVE_MINUTES")
    void update_zzInvalidResolveMinutes() {
        assertThatThrownBy(() -> service.update(1L, 100L, new UpdateRequest(60, 30, null)))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(CsErrorCode.INVALID_RESOLVE_MINUTES);
    }

    @Test
    @DisplayName("update: id 不存在 → SLA_CONFIG_NOT_FOUND")
    void update_notFound() {
        when(mapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(1L, 99L, new UpdateRequest(30, 240, null)))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(CsErrorCode.SLA_CONFIG_NOT_FOUND);
    }

    @Test
    @DisplayName("get: 跨租户访问隔离 → SLA_CONFIG_NOT_FOUND")
    void get_tenantIsolation() {
        SlaConfig row = new SlaConfig();
        row.setId(100L);
        row.setTenantId(2L);
        when(mapper.selectById(100L)).thenReturn(row);

        assertThatThrownBy(() -> service.get(1L, 100L))
            .isInstanceOf(CsException.class)
            .extracting("errorCode")
            .isEqualTo(CsErrorCode.SLA_CONFIG_NOT_FOUND);
    }
}