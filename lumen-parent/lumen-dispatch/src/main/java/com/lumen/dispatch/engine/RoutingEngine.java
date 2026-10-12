package com.lumen.dispatch.engine;

import com.lumen.dispatch.entity.WmsDeliveryStop;

import java.util.List;

/**
 * 排线引擎接口.
 *
 * <p>负责把一组配送站点按访问顺序排序. 4.1 PR4 提供 mock NN 实现
 * （{@link com.lumen.dispatch.engine.impl.MockRoutingEngine}, 按 stop_no
 * 升序），5.x 接高德/Google 后切到真实 TSP 实现.
 *
 * <p>路径选择由 {@link RoutingEngineProperties#routeEngine} 控制（默认
 * {@code "mock"}）.
 */
public interface RoutingEngine {

    /**
     * 排线 — 输入仓库 ID + 站点列表，返回访问顺序 (站点 ID 列表).
     *
     * <p>返回的列表中站点顺序即为推荐访问顺序；站点 ID 列表与输入列表可能
     * 长度相同但元素顺序不同（真实 TSP 优化结果）.
     *
     * @param warehouseId 起始仓库 ID（保留入参以备真实 TSP 使用）
     * @param stops       候选站点（未必排序）
     * @return 访问顺序的站点 ID 列表（空列表 → 空结果，不抛异常）
     */
    List<Long> plan(Long warehouseId, List<WmsDeliveryStop> stops);
}
