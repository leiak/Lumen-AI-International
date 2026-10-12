package com.lumen.integration;

import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.CreateDeliveryExceptionRequest;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.InvestigateRequest;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.ReopenRequest;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.ResolveRequest;
import com.lumen.dispatch.entity.WmsDeliveryException;
import com.lumen.dispatch.service.DeliveryExceptionService;
import com.lumen.extension.state.StateMachineException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DeliveryExceptionWorkOrder 集成测试 (Iteration 4.1 PR4).
 *
 * <p>覆盖 3 个场景：
 * <ul>
 *   <li>完整流: OPEN → INVESTIGATING → RESOLVED → CLOSED</li>
 *   <li>REOPEN: RESOLVED → INVESTIGATING（并可再次 resolve）</li>
 *   <li>close 边界: OPEN 不能直接 close</li>
 * </ul>
 */
@DisplayName("DeliveryExceptionWorkOrder 集成测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeliveryExceptionFlowIT extends IntegrationBase {

    @Autowired private DeliveryExceptionService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @Order(1)
    @DisplayName("exceptionFlow_open_investigate_resolve_close: 4 步 happy path")
    void open_investigate_resolve_close() {
        CreateDeliveryExceptionRequest createReq = new CreateDeliveryExceptionRequest();
        createReq.setOrderId(99L);  // fake order id — exception work order only references; no FK
        createReq.setType("DAMAGE");
        createReq.setDescription("货损报告");

        WmsDeliveryException ex = service.create(createReq);
        assertThat(ex.getStatus()).isEqualTo(DispatchConstants.EX_OPEN);

        WmsDeliveryException investigated = service.investigate(ex.getId(),
                investigateReq("客服Alice"));
        assertThat(investigated.getStatus()).isEqualTo(DispatchConstants.EX_INVESTIGATING);
        assertThat(investigated.getHandler()).isEqualTo("客服Alice");

        WmsDeliveryException resolved = service.resolve(ex.getId(),
                resolveReq("已重新发货"));
        assertThat(resolved.getStatus()).isEqualTo(DispatchConstants.EX_RESOLVED);
        assertThat(resolved.getResolvedAt()).isNotNull();
        assertThat(resolved.getDescription()).contains("已重新发货");

        WmsDeliveryException closed = service.close(ex.getId());
        assertThat(closed.getStatus()).isEqualTo(DispatchConstants.EX_CLOSED);
    }

    @Test
    @Order(2)
    @DisplayName("exceptionFlow_reopen_resolvesAgain: RESOLVED → CLOSED → reopen(OPEN) → 二次 RESOLVED")
    void reopen_resolvesAgain() {
        CreateDeliveryExceptionRequest createReq = new CreateDeliveryExceptionRequest();
        createReq.setOrderId(99L);
        createReq.setType("OVERTIME");
        createReq.setDescription("超时未送达");
        WmsDeliveryException ex = service.create(createReq);

        service.investigate(ex.getId(), investigateReq("客服Bob"));
        service.resolve(ex.getId(), resolveReq("已派新司机"));

        // V20 状态机定义 reopen = CLOSED → OPEN；
        // 完整二次处理链路：RESOLVED → CLOSED → reopen(OPEN) → investigate → resolve
        service.close(ex.getId());

        ReopenRequest reopenReq = new ReopenRequest();
        reopenReq.setReason("客户再次投诉");
        WmsDeliveryException reopened = service.reopen(ex.getId(), reopenReq);
        assertThat(reopened.getStatus()).isEqualTo(DispatchConstants.EX_OPEN);
        assertThat(reopened.getResolvedAt()).isNull();

        // 重新走调查 + 解决
        WmsDeliveryException investigated = service.investigate(ex.getId(),
                investigateReq("客服Charlie"));
        assertThat(investigated.getStatus()).isEqualTo(DispatchConstants.EX_INVESTIGATING);

        WmsDeliveryException reResolved = service.resolve(ex.getId(),
                resolveReq("再次重新发货"));
        assertThat(reResolved.getStatus()).isEqualTo(DispatchConstants.EX_RESOLVED);
    }

    @Test
    @Order(3)
    @DisplayName("exceptionFlow_closeFromOpen_throws: OPEN 状态下 close 抛 StateMachineException")
    void closeFromOpen_throws() {
        CreateDeliveryExceptionRequest createReq = new CreateDeliveryExceptionRequest();
        createReq.setOrderId(99L);
        createReq.setType("ADDR_ERR");
        createReq.setDescription("地址错误");
        WmsDeliveryException ex = service.create(createReq);
        assertThat(ex.getStatus()).isEqualTo(DispatchConstants.EX_OPEN);

        assertThatThrownBy(() -> service.close(ex.getId()))
                .isInstanceOf(StateMachineException.class);
    }

    private static InvestigateRequest investigateReq(String handler) {
        InvestigateRequest r = new InvestigateRequest();
        r.setHandler(handler);
        return r;
    }

    private static ResolveRequest resolveReq(String notes) {
        ResolveRequest r = new ResolveRequest();
        r.setResolutionNotes(notes);
        return r;
    }
}
