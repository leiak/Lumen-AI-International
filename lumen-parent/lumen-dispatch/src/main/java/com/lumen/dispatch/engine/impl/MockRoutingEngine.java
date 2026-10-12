package com.lumen.dispatch.engine.impl;

import com.lumen.dispatch.engine.RoutingEngine;
import com.lumen.dispatch.entity.WmsDeliveryStop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Mock 排线引擎 — 仅按 stop_no 升序返回站点 ID (无地图 / 无 TSP 优化).
 *
 * <p>由 {@code lumen.dispatch.routing.route-engine=mock} 切换 (默认). 5.x
 * 接高德/Google 时另写一个 {@code MapRoutingEngine} 并切到
 * {@code route-engine=map}.
 *
 * <p>实现细节：空列表返回空列表，不抛异常；输入 null 视作空列表.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "lumen.dispatch.routing.route-engine", havingValue = "mock", matchIfMissing = true)
public class MockRoutingEngine implements RoutingEngine {

    @Override
    public List<Long> plan(Long warehouseId, List<WmsDeliveryStop> stops) {
        if (stops == null || stops.isEmpty()) {
            log.debug("MockRoutingEngine.plan: empty input (warehouseId={})", warehouseId);
            return List.of();
        }
        return stops.stream()
                .filter(s -> s != null && s.getStopNo() != null && s.getId() != null)
                .sorted(Comparator.comparingInt(WmsDeliveryStop::getStopNo))
                .map(WmsDeliveryStop::getId)
                .toList();
    }
}
