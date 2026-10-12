package com.lumen.dispatch.engine;

import com.lumen.dispatch.engine.impl.MockRoutingEngine;
import com.lumen.dispatch.entity.WmsDeliveryStop;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MockRoutingEngine 单元测试 (Iteration 4.1 PR4).
 *
 * <p>覆盖 NN 行为：空列表 + 按 stop_no 升序输出。
 */
@DisplayName("MockRoutingEngine 单元测试")
class MockRoutingEngineTest {

    private final MockRoutingEngine engine = new MockRoutingEngine();

    @Test
    @DisplayName("empty input: 返回空列表不抛异常")
    void empty() {
        List<Long> plan = engine.plan(1L, List.of());
        assertThat(plan).isEmpty();
        // also null-safe
        assertThat(engine.plan(1L, null)).isEmpty();
    }

    @Test
    @DisplayName("multi-stop returns ids in stop_no ascending order")
    void multiStopAscending() {
        WmsDeliveryStop s1 = new WmsDeliveryStop();
        s1.setId(100L);
        s1.setStopNo(3);
        WmsDeliveryStop s2 = new WmsDeliveryStop();
        s2.setId(200L);
        s2.setStopNo(1);
        WmsDeliveryStop s3 = new WmsDeliveryStop();
        s3.setId(300L);
        s3.setStopNo(2);

        // pass unordered
        List<WmsDeliveryStop> input = new ArrayList<>();
        input.add(s1);
        input.add(s2);
        input.add(s3);

        List<Long> plan = engine.plan(1L, input);

        assertThat(plan).containsExactly(200L, 300L, 100L);
    }
}
