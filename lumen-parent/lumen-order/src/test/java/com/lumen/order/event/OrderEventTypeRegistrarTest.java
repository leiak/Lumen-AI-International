package com.lumen.order.event;

import com.lumen.extension.outbox.DomainEvent;
import com.lumen.order.OrderConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OrderEventTypeRegistrar 单元测试")
class OrderEventTypeRegistrarTest {

    @Test
    @DisplayName("registeredTypes: order.state.changed → OrderStateChangedEvent, key 与事件实例的 eventType() 逐字相等")
    void registeredTypes_keyMatchesEventType() {
        OrderEventTypeRegistrar registrar = new OrderEventTypeRegistrar();

        Map<String, Class<? extends DomainEvent>> types = registrar.registeredTypes();

        OrderStateChangedEvent event = new OrderStateChangedEvent(
                1L, 100L, "ORD-20261110-000001",
                OrderConstants.STATUS_DRAFT, OrderConstants.STATUS_PENDING_CONFIRM, "submit",
                null, 42L, BigDecimal.valueOf(1000), BigDecimal.valueOf(12.5), BigDecimal.TEN,
                BigDecimal.ZERO, "CNY", null);

        // key 用实例的 eventType() 去查 —— registrar 里写错字符串就立刻红
        assertThat(types).containsEntry(event.eventType(), OrderStateChangedEvent.class);
        assertThat(types).hasSize(1);
    }
}
