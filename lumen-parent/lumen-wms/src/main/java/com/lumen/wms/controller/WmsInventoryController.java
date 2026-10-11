package com.lumen.wms.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.wms.entity.WmsInventory;
import com.lumen.wms.service.WmsInventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 库存账本查询 REST 控制器.
 *
 * <p>当前 PR 仅暴露只读查询（库存余额会被 {@code InboundService} /
 * {@code OutboundService} / {@code AdjustService} 等业务流改动, 不允许
 * 前端直接 POST/PUT/DELETE). 写入路径接入计划在 PR3 一并落地.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/wms/inventories")
@RequiredArgsConstructor
public class WmsInventoryController {

    private final WmsInventoryService service;

    /**
     * 分页查询库存余额. 必须提供 {@code warehouseId} 或 {@code itemId} 之一:
     * <ul>
     *   <li>{@code itemId} 优先 — 按货品查看其在所有仓库的分布</li>
     *   <li>否则 {@code warehouseId} — 查看该仓库下的全部库位余额</li>
     * </ul>
     */
    @GetMapping
    @PreAuthorize("hasAuthority('wms:inventory:list')")
    public R<PageResult<WmsInventory>> page(@RequestParam(required = false) Long warehouseId,
                                            @RequestParam(required = false) Long itemId,
                                            @RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "20") long pageSize) {
        PageResult<WmsInventory> result;
        if (itemId != null) {
            result = service.pageByItem(itemId, pageNum, pageSize);
        } else if (warehouseId != null) {
            result = service.pageByWarehouse(warehouseId, pageNum, pageSize);
        } else {
            throw new IllegalArgumentException("either warehouseId or itemId is required");
        }
        return R.ok(result);
    }

    /**
     * 货品维度余额汇总 — 按 {@code itemId} 分组聚合 {@code qty} 与
     * {@code lockedQty}, 用于"全公司库存总览"等场景.
     *
     * <p>当前实现仅占位返回空 map, 完整 SQL 聚合在 PR3 库存看板一并接入.
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('wms:inventory:summary')")
    public R<Map<String, Object>> summary() {
        log.debug("inventory summary requested (placeholder)");
        return R.ok(Map.of());
    }
}
