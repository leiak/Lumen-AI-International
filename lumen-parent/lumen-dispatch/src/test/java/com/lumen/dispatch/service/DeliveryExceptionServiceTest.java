package com.lumen.dispatch.service;

import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.CreateDeliveryExceptionRequest;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.InvestigateRequest;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.ReopenRequest;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.ResolveRequest;
import com.lumen.dispatch.entity.WmsDeliveryException;
import com.lumen.dispatch.exception.DeliveryExceptionNotFoundException;
import com.lumen.dispatch.mapper.WmsDeliveryExceptionMapper;
import com.lumen.dispatch.service.impl.DeliveryExceptionServiceImpl;
import com.lumen.extension.outbox.DomainEvent;
import com.lumen.extension.outbox.EventBus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeliveryExceptionService 单元测试 (Iteration 4.1 PR4).
 *
 * <p>覆盖 5 个状态机迁移 + CRUD。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeliveryExceptionService 单元测试")
class DeliveryExceptionServiceTest {

    @Mock private WmsDeliveryExceptionMapper mapper;
    @Mock private StateMachineEngine stateMachineEngine;
    @Mock private EventBus eventBus;

    @InjectMocks private DeliveryExceptionServiceImpl service;

    private WmsDeliveryException existing;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L);
        existing = new WmsDeliveryException();
        existing.setId(50L);
        existing.setTenantId(1L);
        setOrderId(existing, 100L);
        existing.setType("REJECTED");
        existing.setStatus(DispatchConstants.EX_OPEN);
        existing.setDescription("客户拒收");
        lenient().when(mapper.selectById(50L)).thenReturn(existing);
        lenient().when(mapper.selectById(99L)).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("create: 默认 OPEN + tenant 回填")
    void create_defaultsOpen() {
        CreateDeliveryExceptionRequest req = new CreateDeliveryExceptionRequest();
        req.setOrderId(100L);
        req.setType("DAMAGE");
        req.setDescription("货损");

        when(mapper.insert(any(WmsDeliveryException.class))).thenAnswer(inv -> {
            WmsDeliveryException arg = inv.getArgument(0);
            arg.setId(700L);
            return 1;
        });

        WmsDeliveryException result = service.create(req);

        assertThat(result.getStatus()).isEqualTo(DispatchConstants.EX_OPEN);
        assertThat(result.getTenantId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("get: id 不存在 → DeliveryExceptionNotFoundException")
    void get_notFound() {
        assertThatThrownBy(() -> service.get(99L))
                .isInstanceOf(DeliveryExceptionNotFoundException.class);
    }

    @Test
    @DisplayName("investigate: OPEN → INVESTIGATING + handler 写入")
    void investigate_openToInvestigating() {
        InvestigateRequest req = new InvestigateRequest();
        req.setHandler("客服Alice");

        WmsDeliveryException result = service.investigate(50L, req);

        assertThat(result.getStatus()).isEqualTo(DispatchConstants.EX_INVESTIGATING);
        assertThat(result.getHandler()).isEqualTo("客服Alice");
        // PR2: outbox event 验证
        verify(eventBus).publish(any(DomainEvent.class));
    }

    @Test
    @DisplayName("resolve: INVESTIGATING → RESOLVED + resolvedAt + notes 拼接")
    void resolve_investigatingToResolved() {
        existing.setStatus(DispatchConstants.EX_INVESTIGATING);
        when(mapper.selectById(50L)).thenReturn(existing);

        ResolveRequest req = new ResolveRequest();
        req.setResolutionNotes("已重新派车");

        WmsDeliveryException result = service.resolve(50L, req);

        assertThat(result.getStatus()).isEqualTo(DispatchConstants.EX_RESOLVED);
        assertThat(result.getResolvedAt()).isNotNull();
        assertThat(result.getDescription()).contains("已重新派车");
        // PR2: outbox event 验证
        verify(eventBus).publish(any(DomainEvent.class));
    }

    @Test
    @DisplayName("close: RESOLVED → CLOSED")
    void close_resolvedToClosed() {
        existing.setStatus(DispatchConstants.EX_RESOLVED);
        when(mapper.selectById(50L)).thenReturn(existing);

        WmsDeliveryException result = service.close(50L);

        assertThat(result.getStatus()).isEqualTo(DispatchConstants.EX_CLOSED);
        // PR2: outbox event 验证
        verify(eventBus).publish(any(DomainEvent.class));
    }

    @Test
    @DisplayName("reopen: CLOSED → OPEN (V20 状态机定义；调用方随后显式 investigate)")
    void reopen_resolvedToInvestigating() {
        existing.setStatus(DispatchConstants.EX_CLOSED);
        when(mapper.selectById(50L)).thenReturn(existing);
        // service 走 update(null, wrapper) 路径，不会就地改 existing；这里
        // 用 doAnswer 在 update 调用后把状态翻成 OPEN 并清空 resolvedAt，
        // 模拟"DB 已被 UpdateWrapper 改写"的真实行为。
        org.mockito.Mockito.doAnswer(inv -> {
            existing.setStatus(DispatchConstants.EX_OPEN);
            existing.setResolvedAt(null);
            existing.setDescription(
                    (existing.getDescription() == null ? "" : existing.getDescription() + "\n--- REOPENED ---\n")
                            + "客户申诉");
            return 1;
        }).when(mapper).update(org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));

        ReopenRequest req = new ReopenRequest();
        req.setReason("客户申诉");

        WmsDeliveryException result = service.reopen(50L, req);

        assertThat(result.getStatus()).isEqualTo(DispatchConstants.EX_OPEN);
        assertThat(result.getResolvedAt()).isNull();
        assertThat(result.getDescription()).contains("客户申诉");
        // PR2: outbox event 验证
        verify(eventBus).publish(any(DomainEvent.class));
    }

    @Test
    @DisplayName("close: 当前 OPEN (不允许) → StateMachineException")
    void close_fromOpenRejected() {
        doThrow(new StateMachineException("OPEN -> CLOSED not allowed"))
                .when(stateMachineEngine).assertTransition(
                        DispatchConstants.SM_EXCEPTION_WORK_ORDER,
                        DispatchConstants.EX_OPEN, DispatchConstants.EX_CLOSED);

        assertThatThrownBy(() -> service.close(50L))
                .isInstanceOf(StateMachineException.class);
        verify(mapper, org.mockito.Mockito.never()).updateById(any(WmsDeliveryException.class));
    }

    // helper
    private static void setOrderId(WmsDeliveryException e, Long id) {
        try {
            java.lang.reflect.Field f = WmsDeliveryException.class.getDeclaredField("orderId");
            f.setAccessible(true);
            f.set(e, id);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
