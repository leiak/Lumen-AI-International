package com.lumen.common.state;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class StateMachineRegistry {
    private final ConcurrentMap<String, StateMachine<?, ?, ?>> map = new ConcurrentHashMap<>();
    public <S, E, C> void register(StateMachine<S, E, C> sm) { map.put(sm.getCode(), sm); }
    @SuppressWarnings("unchecked")
    public <S, E, C> StateMachine<S, E, C> get(String code) { return (StateMachine<S, E, C>) map.get(code); }
}