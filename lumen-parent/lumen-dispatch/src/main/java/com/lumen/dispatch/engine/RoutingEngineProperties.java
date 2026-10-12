package com.lumen.dispatch.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 排线引擎配置.
 *
 * <p>绑定的属性前缀 {@code lumen.dispatch.routing}. 默认路由实现为
 * {@code "mock"}（仅按 stop_no 排序）;5.x 接地图 TSP 后改为
 * {@code "map"}，并实现 {@code MapRoutingEngine}.
 */
@Component
@ConfigurationProperties(prefix = "lumen.dispatch.routing")
public class RoutingEngineProperties {

    /**
     * 排线引擎实现标识. 默认 {@code "mock"} — 使用 {@code MockRoutingEngine}.
     * 备选 {@code "map"} — 留待 5.x EP-D1 接入.
     */
    private String routeEngine = "mock";

    public String getRouteEngine() {
        return routeEngine;
    }

    public void setRouteEngine(String routeEngine) {
        this.routeEngine = routeEngine;
    }
}
