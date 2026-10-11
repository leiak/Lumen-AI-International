package com.lumen.wms.service;

import com.lumen.common.api.PageResult;
import com.lumen.wms.entity.WmsInventory;

import java.math.BigDecimal;

/**
 * 库存账本服务.
 *
 * <p>负责 wms_inventory 表的余额 / 锁定 / 调整等原子操作.
 * 包含两大类方法：
 * <ul>
 *   <li>只读查询（{@code getOrInit} / {@code get} / {@code pageByWarehouse} /
 *       {@code pageByItem}）</li>
 *   <li>原子变更（{@code addQty} / {@code subtractQty} / {@code lockQty} /
 *       {@code releaseLock} / {@code flushLock} / {@code adjustDelta}），
 *       内部使用 MyBatis-Plus {@code @Version} 乐观锁保证并发安全</li>
 * </ul>
 *
 * <p>账本变更一律要求传入 (warehouseId, locationId, itemId) 三元组 —
 * 不依赖外键引用业务主数据，调用方需自行保证 ID 已在主数据中存在.
 */
public interface WmsInventoryService {

    // ====================================================================
    // Read
    // ====================================================================

    /**
     * 按 (warehouse, location, item) 查询库存行；若不存在则插入一条零余额账本
     * 并返回. 用于 outbound pick 等"幂等首次见到"的场景.
     */
    WmsInventory getOrInit(Long warehouseId, Long locationId, Long itemId);

    /**
     * 按 (warehouse, location, item) 查询库存行；不存在抛
     * {@code InventoryNotFoundException}.
     */
    WmsInventory get(Long warehouseId, Long locationId, Long itemId);

    /**
     * 分页列出某仓库下全部库存行.
     */
    PageResult<WmsInventory> pageByWarehouse(Long warehouseId, long pageNum, long pageSize);

    /**
     * 分页列出某货品在所有仓库/库位的余额（按仓库聚合场景常用）.
     */
    PageResult<WmsInventory> pageByItem(Long itemId, long pageNum, long pageSize);

    // ====================================================================
    // Mutate (atomic; transactional)
    // ====================================================================

    /**
     * 增加库存（inbound receive）— qty 增量必须 &gt; 0.
     */
    WmsInventory addQty(Long warehouseId, Long locationId, Long itemId, BigDecimal qty);

    /**
     * 扣减库存（outbound ship）— qty 增量必须 &gt; 0；不足抛
     * {@code InsufficientInventoryException}.
     */
    WmsInventory subtractQty(Long warehouseId, Long locationId, Long itemId, BigDecimal qty);

    /**
     * 锁定库存（reserve for outbound picking）— 增量必须 &gt; 0；
     * 加锁后 {@code locked_qty += qty}，不可超过当前 {@code qty}.
     */
    WmsInventory lockQty(Long warehouseId, Long locationId, Long itemId, BigDecimal qty);

    /**
     * 释放锁定 — 增量必须 &gt; 0；不允许使 {@code locked_qty} 低于 0.
     */
    WmsInventory releaseLock(Long warehouseId, Long locationId, Long itemId, BigDecimal qty);

    /**
     * 把当前 {@code locked_qty} 全部转为消耗：{@code qty -= locked_qty},
     * {@code locked_qty = 0}. 用于出库复核完成后的最终核销.
     */
    WmsInventory flushLock(Long warehouseId, Long locationId, Long itemId);

    /**
     * 库存调整 — {@code delta} 可正可负但不为 0；调整后保证
     * {@code qty >= 0} 且 {@code qty >= locked_qty}.
     */
    WmsInventory adjustDelta(Long warehouseId, Long locationId, Long itemId, BigDecimal delta);
}
