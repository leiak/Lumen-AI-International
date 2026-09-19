package com.lumen.integration;

import com.lumen.extension.state.StateMachineEngine;
import com.lumen.extension.state.StateMachineException;
import com.lumen.extension.state.StateMachineRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 集成测试：状态机 Engine + Registry。
 *
 * <p>{@code IntegrationBase} 已经标注了 {@code @SpringBootTest(webEnvironment=RANDOM_PORT)}
 * 和 testcontainers 子类化，所以本 IT 不需要重复 {@code @SpringBootTest}。
 */
class StateConfigIT extends IntegrationBase {

    @Autowired StateMachineEngine engine;
    @Autowired StateMachineRegistry registry;

    @Test
    void assertTransition_allowsValid() {
        engine.assertTransition("sys_country", "DRAFT", "ACTIVE");
        engine.assertTransition("sys_country", "ACTIVE", "FROZEN");
    }

    @Test
    void assertTransition_rejectsInvalid() {
        assertThatThrownBy(() -> engine.assertTransition("sys_country", "DRAFT", "FROZEN"))
                .isInstanceOf(StateMachineException.class)
                .hasMessageContaining("sys_country")
                .hasMessageContaining("DRAFT")
                .hasMessageContaining("FROZEN");
    }

    @Test
    void allowedNext_returnsValidTargets() {
        List<String> next = engine.allowedNext("sys_country", "DRAFT");
        assertThat(next).containsExactlyInAnyOrder("ACTIVE", "VOID");

        List<String> nextFromFrozen = engine.allowedNext("sys_country", "FROZEN");
        assertThat(nextFromFrozen).containsExactlyInAnyOrder("ACTIVE", "VOID");
    }

    @Test
    void registry_cacheInvalidate() {
        // 第一次加载
        engine.allowedNext("sys_country", "DRAFT");
        // 强制 invalidate 后再加载不应抛异常
        registry.invalidate("sys_country");
        List<String> next = engine.allowedNext("sys_country", "DRAFT");
        assertThat(next).isNotEmpty();
    }
}
