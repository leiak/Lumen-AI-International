package com.lumen.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.wms.entity.WmsInventory;
import com.lumen.wms.exception.InsufficientInventoryException;
import com.lumen.wms.exception.InventoryNotFoundException;
import com.lumen.wms.mapper.WmsInventoryMapper;
import com.lumen.wms.service.WmsInventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存账本服务实现.
 *
 * <p>所有变更方法都遵循同一个范式：
 * <ol>
 *   <li>{@code getOrInit(warehouseId, locationId, itemId)} 读出（必要时插入）账本行</li>
 *   <li>校验新值满足 {@code qty >= 0} 且 {@code qty >= locked_qty}</li>
 *   <li>设置 {@code lastMovementAt} 后 {@code mapper.updateById}，
 *       触发 MyBatis-Plus {@code @Version} 乐观锁重试</li>
 * </ol>
 *
 * <p>{@code getOrInit} 采用 select + insert + 重选的 UPSERT 模式，
 * 在并发首次写入时通过 {@link DuplicateKeyException} 兜底：
 * catch 后 re-select 真实写入者留下的行.
 *
 * <p>{@code addQty} / {@code subtractQty} / {@code flushLock} 都会刷新
 * {@code lastMovementAt} — 用于下游审计/报表追溯最近一次余额变更.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WmsInventoryServiceImpl implements WmsInventoryService {

    private final WmsInventoryMapper mapper;

    // ====================================================================
    // Read
    // ====================================================================

    @Override
    @Transactional
    public WmsInventory getOrInit(Long warehouseId, Long locationId, Long itemId) {
        WmsInventory existing = mapper.selectOne(buildWrapper(warehouseId, locationId, itemId));
        if (existing != null) return existing;

        WmsInventory fresh = new WmsInventory();
        fresh.setWarehouseId(warehouseId);
        fresh.setLocationId(locationId);
        fresh.setItemId(itemId);
        fresh.setQty(BigDecimal.ZERO);
        fresh.setLockedQty(BigDecimal.ZERO);
        fresh.setLastMovementAt(LocalDateTime.now());
        // tenant_id 由 MyMetaObjectHandler 读取 TenantContext 自动填
        if (fresh.getTenantId() == null) {
            fresh.setTenantId(TenantContext.get());
        }
        try {
            mapper.insert(fresh);
            return fresh;
        } catch (DuplicateKeyException e) {
            // 并发首次写入：另一个线程先 INSERT 成功，重选一次
            log.debug("inventory row already inserted by concurrent thread: wh={} loc={} item={}",
                    warehouseId, locationId, itemId);
            WmsInventory raced = mapper.selectOne(buildWrapper(warehouseId, locationId, itemId));
            if (raced == null) {
                // 极小概率：对方事务尚未提交且 Serializable 隔离级别下读不到 —
                // 这里仍兜底抛 NOT_FOUND，让上游重试
                throw new InventoryNotFoundException(warehouseId, locationId, itemId);
            }
            return raced;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public WmsInventory get(Long warehouseId, Long locationId, Long itemId) {
        WmsInventory inv = mapper.selectOne(buildWrapper(warehouseId, locationId, itemId));
        if (inv == null) throw new InventoryNotFoundException(warehouseId, locationId, itemId);
        return inv;
    }

    @Override
    public PageResult<WmsInventory> pageByWarehouse(Long warehouseId, long pageNum, long pageSize) {
        Page<WmsInventory> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<WmsInventory> qw = new LambdaQueryWrapper<>();
        qw.eq(WmsInventory::getWarehouseId, warehouseId);
        qw.orderByAsc(WmsInventory::getLocationId);
        qw.orderByAsc(WmsInventory::getItemId);
        Page<WmsInventory> res = mapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), pageNum, pageSize);
    }

    @Override
    public PageResult<WmsInventory> pageByItem(Long itemId, long pageNum, long pageSize) {
        Page<WmsInventory> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<WmsInventory> qw = new LambdaQueryWrapper<>();
        qw.eq(WmsInventory::getItemId, itemId);
        qw.orderByAsc(WmsInventory::getWarehouseId);
        qw.orderByAsc(WmsInventory::getLocationId);
        Page<WmsInventory> res = mapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), pageNum, pageSize);
    }

    // ====================================================================
    // Mutate
    // ====================================================================

    @Override
    @Transactional
    public WmsInventory addQty(Long warehouseId, Long locationId, Long itemId, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            throw new IllegalArgumentException("qty must be positive: " + qty);
        }
        return mutateQty(warehouseId, locationId, itemId, qty, true);
    }

    @Override
    @Transactional
    public WmsInventory subtractQty(Long warehouseId, Long locationId, Long itemId, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            throw new IllegalArgumentException("qty must be positive: " + qty);
        }
        return mutateQty(warehouseId, locationId, itemId, qty, false);
    }

    @Override
    @Transactional
    public WmsInventory lockQty(Long warehouseId, Long locationId, Long itemId, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            throw new IllegalArgumentException("qty must be positive: " + qty);
        }
        WmsInventory inv = getOrInit(warehouseId, locationId, itemId);
        BigDecimal newLocked = inv.getLockedQty().add(qty);
        if (inv.getQty().compareTo(newLocked) < 0) {
            throw new InsufficientInventoryException(warehouseId, locationId, itemId,
                    qty, inv.getQty().subtract(inv.getLockedQty()));
        }
        inv.setLockedQty(newLocked);
        inv.setLastMovementAt(LocalDateTime.now());
        mapper.updateById(inv); // @Version 乐观锁
        return inv;
    }

    @Override
    @Transactional
    public WmsInventory releaseLock(Long warehouseId, Long locationId, Long itemId, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            throw new IllegalArgumentException("qty must be positive: " + qty);
        }
        WmsInventory inv = get(warehouseId, locationId, itemId);
        BigDecimal newLocked = inv.getLockedQty().subtract(qty);
        if (newLocked.signum() < 0) {
            throw new InsufficientInventoryException(warehouseId, locationId, itemId,
                    qty, inv.getLockedQty());
        }
        inv.setLockedQty(newLocked);
        inv.setLastMovementAt(LocalDateTime.now());
        mapper.updateById(inv);
        return inv;
    }

    @Override
    @Transactional
    public WmsInventory flushLock(Long warehouseId, Long locationId, Long itemId) {
        WmsInventory inv = get(warehouseId, locationId, itemId);
        BigDecimal consume = inv.getLockedQty();
        BigDecimal newQty = inv.getQty().subtract(consume);
        if (newQty.signum() < 0) {
            throw new InsufficientInventoryException(warehouseId, locationId, itemId,
                    consume, inv.getQty());
        }
        inv.setQty(newQty);
        inv.setLockedQty(BigDecimal.ZERO);
        inv.setLastMovementAt(LocalDateTime.now());
        mapper.updateById(inv);
        return inv;
    }

    @Override
    @Transactional
    public WmsInventory adjustDelta(Long warehouseId, Long locationId, Long itemId, BigDecimal delta) {
        if (delta == null || delta.signum() == 0) {
            throw new IllegalArgumentException("delta cannot be zero or null: " + delta);
        }
        WmsInventory inv = getOrInit(warehouseId, locationId, itemId);
        BigDecimal newQty = inv.getQty().add(delta);
        if (newQty.signum() < 0) {
            throw new InsufficientInventoryException(warehouseId, locationId, itemId,
                    delta.abs(), inv.getQty());
        }
        if (newQty.compareTo(inv.getLockedQty()) < 0) {
            throw new InsufficientInventoryException(warehouseId, locationId, itemId,
                    inv.getLockedQty().subtract(newQty), inv.getQty());
        }
        inv.setQty(newQty);
        inv.setLastMovementAt(LocalDateTime.now());
        mapper.updateById(inv);
        return inv;
    }

    // ====================================================================
    // helpers
    // ====================================================================

    /**
     * 增减 qty 的公共路径。{@code positive=true} 为 addQty，{@code false} 为 subtractQty.
     */
    private WmsInventory mutateQty(Long warehouseId, Long locationId, Long itemId,
                                   BigDecimal qty, boolean positive) {
        WmsInventory inv = getOrInit(warehouseId, locationId, itemId);
        BigDecimal newQty = positive
                ? inv.getQty().add(qty)
                : inv.getQty().subtract(qty);
        if (newQty.signum() < 0) {
            throw new InsufficientInventoryException(warehouseId, locationId, itemId,
                    qty, inv.getQty());
        }
        if (newQty.compareTo(inv.getLockedQty()) < 0) {
            // 不允许"扣减后 qty < locked_qty" — 那意味着有锁定量凭空裸露
            throw new InsufficientInventoryException(warehouseId, locationId, itemId,
                    inv.getLockedQty().subtract(newQty), inv.getQty());
        }
        inv.setQty(newQty);
        inv.setLastMovementAt(LocalDateTime.now());
        mapper.updateById(inv);
        return inv;
    }

    /**
     * 构造 (warehouse, location, item) 三元组的查询条件.
     *
     * <p>{@code tenant_id} 由 {@code TenantLineInnerInterceptor} 自动注入；
     * {@code deleted=0} 由 {@code @TableLogic} 自动注入 —— 这里不必再写.
     */
    private LambdaQueryWrapper<WmsInventory> buildWrapper(Long warehouseId, Long locationId, Long itemId) {
        return new LambdaQueryWrapper<WmsInventory>()
                .eq(WmsInventory::getWarehouseId, warehouseId)
                .eq(WmsInventory::getLocationId, locationId)
                .eq(WmsInventory::getItemId, itemId);
    }
}
