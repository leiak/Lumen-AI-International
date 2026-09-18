package com.lumen.common.state;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StateMachineTest {
    enum S { DRAFT, ACTIVE, CLOSED }
    enum E { ACTIVATE, CLOSE }

    @Test void fire_happyPath() {
        StateMachine<S, E, Void> sm = new StateMachine<S, E, Void>("t", S.DRAFT)
                .addTransition(S.DRAFT, E.ACTIVATE, S.ACTIVE, null, "publish")
                .addTransition(S.ACTIVE, E.CLOSE, S.CLOSED, null, "close");
        assertThat(sm.fire(S.DRAFT, E.ACTIVATE, null)).isEqualTo(S.ACTIVE);
        assertThat(sm.fire(S.ACTIVE, E.CLOSE, null)).isEqualTo(S.CLOSED);
    }

    @Test void fire_invalid_throws() {
        StateMachine<S, E, Void> sm = new StateMachine<S, E, Void>("t", S.DRAFT)
                .addTransition(S.DRAFT, E.ACTIVATE, S.ACTIVE, null, "publish");
        assertThatThrownBy(() -> sm.fire(S.ACTIVE, E.ACTIVATE, null)).hasMessageContaining("非法状态转换");
    }

    @Test void guard_rejects() {
        StateMachine<S, E, Boolean> sm = new StateMachine<S, E, Boolean>("t", S.DRAFT)
                .addTransition(S.DRAFT, E.ACTIVATE, S.ACTIVE, ctx -> Boolean.TRUE.equals(ctx), "ok");
        assertThatThrownBy(() -> sm.fire(S.DRAFT, E.ACTIVATE, false)).hasMessageContaining("守卫失败");
    }
}