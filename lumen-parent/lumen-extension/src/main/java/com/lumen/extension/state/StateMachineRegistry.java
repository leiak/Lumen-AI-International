package com.lumen.extension.state;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component("tableDrivenStateMachineRegistry")
@RequiredArgsConstructor
public class StateMachineRegistry {
    private final StateTransitionMapper mapper;
    private final StateMachineProperties props;
    private Cache<String, Map<String, List<StateTransition>>> cache;

    @PostConstruct
    public void init() {
        cache = Caffeine.newBuilder()
                .expireAfterWrite(props.getCache().getTtlSeconds(), TimeUnit.SECONDS)
                .maximumSize(props.getCache().getMaxSize())
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        cache.invalidateAll();
        log.info("State machine cache warmed up (TTL={}s, maxSize={})",
                props.getCache().getTtlSeconds(), props.getCache().getMaxSize());
    }

    public Map<String, List<StateTransition>> getTransitions(String code) {
        return cache.get(code, this::loadFromDb);
    }

    private Map<String, List<StateTransition>> loadFromDb(String code) {
        List<StateTransition> rows = mapper.selectList(
            Wrappers.<StateTransition>lambdaQuery()
                .eq(StateTransition::getStateMachineCode, code)
                .eq(StateTransition::getDeleted, 0)
                .orderByAsc(StateTransition::getSortOrder)
        );
        return rows.stream().collect(Collectors.groupingBy(StateTransition::getFromState));
    }

    public void invalidate(String code) {
        cache.invalidate(code);
    }
}