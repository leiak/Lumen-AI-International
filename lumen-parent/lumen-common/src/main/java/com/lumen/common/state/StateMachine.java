package com.lumen.common.state;

import lombok.Getter;
import java.util.*;
import java.util.function.Predicate;

@Getter
public class StateMachine<S, E, C> {
    private final String code;
    private final S initial;
    private final Map<S, Map<E, Transition<S, E, C>>> table = new HashMap<>();

    public StateMachine(String code, S initial) { this.code = code; this.initial = initial; }

    public StateMachine<S, E, C> addTransition(S from, E event, S to, Predicate<C> guard, String name) {
        table.computeIfAbsent(from, k -> new HashMap<>()).put(event, new Transition<>(from, event, to, guard, name));
        return this;
    }

    public S fire(S current, E evt, C ctx) {
        Transition<S, E, C> t = table.getOrDefault(current, Map.of()).get(evt);
        if (t == null) throw new com.lumen.common.error.BizException(com.lumen.common.error.CommonErrorCode.BAD_REQUEST, "非法状态转换: " + current + " -" + evt);
        if (t.getGuard() != null && !t.getGuard().test(ctx))
            throw new com.lumen.common.error.BizException(com.lumen.common.error.CommonErrorCode.BAD_REQUEST, "守卫失败: " + t.getName());
        return t.getTo();
    }
}