package com.lumen.extension.state;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class StateMachineEngine {
    private final StateMachineRegistry registry;

    public void assertTransition(String code, String fromState, String toState) {
        List<StateTransition> transitions = registry.getTransitions(code)
                .getOrDefault(fromState, List.of());
        boolean allowed = transitions.stream()
                .anyMatch(t -> Objects.equals(t.getToState(), toState));
        if (!allowed) {
            throw new StateMachineException(
                String.format("状态机 %s 不允许从 %s 转移到 %s", code, fromState, toState)
            );
        }
    }

    public List<String> allowedNext(String code, String fromState) {
        Map<String, List<StateTransition>> map = registry.getTransitions(code);
        List<StateTransition> transitions = map.getOrDefault(fromState, List.of());
        return transitions.stream().map(StateTransition::getToState).distinct().toList();
    }
}