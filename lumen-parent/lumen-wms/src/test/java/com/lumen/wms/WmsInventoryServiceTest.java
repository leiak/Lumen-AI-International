package com.lumen.wms;

import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.wms.entity.WmsInventory;
import com.lumen.wms.exception.InsufficientInventoryException;
import com.lumen.wms.exception.InventoryNotFoundException;
import com.lumen.wms.service.WmsInventoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.startupcheck.MinimumDurationRunningStartupCheckStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WMS 库存账本服务集成测试.
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@code getOrInit} 的幂等首次见到 — 并发场景下靠 UPSERT 兜底</li>
 *   <li>增减 / 锁定 / 释放 / 核销 / 调整 等原子变更的数值正确性</li>
 *   <li>余额不足时抛 {@code InsufficientInventoryException}</li>
 *   <li>账本不存在时抛 {@code InventoryNotFoundException}</li>
 *   <li>{@code pageByWarehouse} / {@code pageByItem} 分页返回非空</li>
 * </ul>
 *
 * <p>约定：
 * <ul>
 *   <li>每个测试都用 {@code freshKey()} 生成基于 {@code nanoTime} 的 (warehouseId,
 *       locationId, itemId) 三元组，避免跨 JVM 重跑 / 跨用例撞
 *       {@code uk_wms_inv_loc_item (tenant_id, warehouse_id, location_id, item_id)}</li>
 *   <li>{@code TenantContext} 在每个测试前手动置 1L（与 production admin 租户一致），
 *       结束后清理 — service 依赖的 MyBatis-Plus 租户拦截器从 threadlocal 取值</li>
 * </ul>
 *
 * <p>Testcontainer 启动一次后会被 failsafe fork 内的所有用例复用（{@code static @Container}
 * + {@code testcontainers.junit.jupiter.Testcontainers}）.
 */
@SpringBootTest(classes = WmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
@DisplayName("WMS 库存账本服务集成测试")
class WmsInventoryServiceTest {

    /**
     * 全 JVM 共享的 MySQL 容器. 与 {@code MasterDataIT} 保持同源，
     * 但本组 IT 不共享事务/数据（每个测试用 {@code freshKey()} 隔离 keys）.
     */
    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("lumen")
            .withUsername("lumen")
            .withPassword("lumen")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
            .withStartupCheckStrategy(new MinimumDurationRunningStartupCheckStrategy(Duration.ofSeconds(1)));

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired WmsInventoryService service;

    /** 测试租户 — 与 MasterDataIT 保持一致. */
    private static final Long TENANT_ID = 1L;

    @BeforeEach
    void setUpTenant() {
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ====================================================================
    // helpers
    // ====================================================================

    /**
     * 基于 nanoTime 生成互不冲突的 (wh, loc, item) 三元组.
     *
     * <p>{@code wms_inventory} 的唯一约束是
     * {@code (tenant_id, warehouse_id, location_id, item_id)}，因此即使跨
     * JVM 重跑同一 IT，只要 nanoTime 不同的三元组不撞到就行. 这里把
     * nanoTime 拆成 3 段保证三段都单调递增.
     */
    private static Long[] freshKey() {
        long n = System.nanoTime();
        return new Long[] { n, n + 1, n + 2 };
    }

    // ====================================================================
    // Read
    // ====================================================================

    @Test
    @DisplayName("getOrInit 在新三元组上创建零余额账本")
    void getOrInit_createsRowWithZeroBalance() {
        Long[] k = freshKey();
        WmsInventory inv = service.getOrInit(k[0], k[1], k[2]);

        assertThat(inv.getId()).isNotNull();
        assertThat(inv.getWarehouseId()).isEqualTo(k[0]);
        assertThat(inv.getLocationId()).isEqualTo(k[1]);
        assertThat(inv.getItemId()).isEqualTo(k[2]);
        assertThat(inv.getQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(inv.getLockedQty()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("getOrInit 二次调用读到首次写入的行，不重复插入")
    void getOrInit_returnsExistingRow() {
        Long[] k = freshKey();
        WmsInventory first = service.getOrInit(k[0], k[1], k[2]);

        WmsInventory second = service.getOrInit(k[0], k[1], k[2]);
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getQty()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("get 在三元组不存在时抛 InventoryNotFoundException")
    void get_throwsWhenMissing() {
        Long[] k = freshKey();
        assertThatThrownBy(() -> service.get(k[0], k[1], k[2]))
                .isInstanceOf(InventoryNotFoundException.class)
                .hasMessageContaining(String.valueOf(k[0]))
                .hasMessageContaining(String.valueOf(k[1]))
                .hasMessageContaining(String.valueOf(k[2]));
    }

    // ====================================================================
    // Mutate — qty
    // ====================================================================

    @Test
    @DisplayName("addQty 在新键上创建行并累加 qty")
    void addQty_createsRowAndIncrements() {
        Long[] k = freshKey();
        WmsInventory inv = service.addQty(k[0], k[1], k[2], new BigDecimal("50"));
        assertThat(inv.getQty()).isEqualByComparingTo("50");
        assertThat(inv.getLockedQty()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("addQty 累加：多次调用 qty 持续增大")
    void addQty_isAdditive() {
        Long[] k = freshKey();
        service.addQty(k[0], k[1], k[2], new BigDecimal("30"));
        WmsInventory inv = service.addQty(k[0], k[1], k[2], new BigDecimal("20"));
        assertThat(inv.getQty()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("subtractQty 减少库存到指定值")
    void subtractQty_reducesStock() {
        Long[] k = freshKey();
        service.addQty(k[0], k[1], k[2], new BigDecimal("100"));
        WmsInventory inv = service.subtractQty(k[0], k[1], k[2], new BigDecimal("30"));
        assertThat(inv.getQty()).isEqualByComparingTo("70");
    }

    @Test
    @DisplayName("subtractQty 超过余额时抛 InsufficientInventoryException")
    void subtractQty_insufficientThrows() {
        Long[] k = freshKey();
        service.addQty(k[0], k[1], k[2], new BigDecimal("10"));
        assertThatThrownBy(() -> service.subtractQty(k[0], k[1], k[2], new BigDecimal("20")))
                .isInstanceOf(InsufficientInventoryException.class);
    }

    @Test
    @DisplayName("subtractQty/0 或负数抛 IllegalArgumentException")
    void subtractQty_nonPositiveRejected() {
        Long[] k = freshKey();
        service.addQty(k[0], k[1], k[2], new BigDecimal("10"));
        assertThatThrownBy(() -> service.subtractQty(k[0], k[1], k[2], BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.subtractQty(k[0], k[1], k[2], new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ====================================================================
    // Mutate — lock / release / flush
    // ====================================================================

    @Test
    @DisplayName("lockQty 预留库存：locked_qty 增加，qty 不变")
    void lockQty_reserves() {
        Long[] k = freshKey();
        service.addQty(k[0], k[1], k[2], new BigDecimal("100"));
        WmsInventory inv = service.lockQty(k[0], k[1], k[2], new BigDecimal("40"));
        assertThat(inv.getLockedQty()).isEqualByComparingTo("40");
        assertThat(inv.getQty()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("lockQty 累计超出可用量时抛 InsufficientInventoryException")
    void lockQty_exceedingStockThrows() {
        Long[] k = freshKey();
        service.addQty(k[0], k[1], k[2], new BigDecimal("10"));
        assertThatThrownBy(() -> service.lockQty(k[0], k[1], k[2], new BigDecimal("20")))
                .isInstanceOf(InsufficientInventoryException.class);
    }

    @Test
    @DisplayName("releaseLock 把之前锁定的库存回填")
    void releaseLock_unreserves() {
        Long[] k = freshKey();
        service.addQty(k[0], k[1], k[2], new BigDecimal("100"));
        service.lockQty(k[0], k[1], k[2], new BigDecimal("40"));
        WmsInventory inv = service.releaseLock(k[0], k[1], k[2], new BigDecimal("15"));
        assertThat(inv.getLockedQty()).isEqualByComparingTo("25");
        assertThat(inv.getQty()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("releaseLock 释放量超过锁定量抛 InsufficientInventoryException")
    void releaseLock_exceedingThrows() {
        Long[] k = freshKey();
        service.addQty(k[0], k[1], k[2], new BigDecimal("100"));
        service.lockQty(k[0], k[1], k[2], new BigDecimal("30"));
        assertThatThrownBy(() -> service.releaseLock(k[0], k[1], k[2], new BigDecimal("40")))
                .isInstanceOf(InsufficientInventoryException.class);
    }

    @Test
    @DisplayName("flushLock 把 locked_qty 转为实际消耗并清零")
    void flushLock_consumesLockedAndZeroes() {
        Long[] k = freshKey();
        service.addQty(k[0], k[1], k[2], new BigDecimal("100"));
        service.lockQty(k[0], k[1], k[2], new BigDecimal("30"));
        WmsInventory inv = service.flushLock(k[0], k[1], k[2]);
        assertThat(inv.getQty()).isEqualByComparingTo("70");
        assertThat(inv.getLockedQty()).isEqualByComparingTo("0");
    }

    // ====================================================================
    // Mutate — adjust delta
    // ====================================================================

    @Test
    @DisplayName("adjustDelta 负向递减 qty")
    void adjustDelta_negative() {
        Long[] k = freshKey();
        service.addQty(k[0], k[1], k[2], new BigDecimal("50"));
        WmsInventory inv = service.adjustDelta(k[0], k[1], k[2], new BigDecimal("-10"));
        assertThat(inv.getQty()).isEqualByComparingTo("40");
    }

    @Test
    @DisplayName("adjustDelta 正向递增 qty")
    void adjustDelta_positive() {
        Long[] k = freshKey();
        service.addQty(k[0], k[1], k[2], new BigDecimal("50"));
        WmsInventory inv = service.adjustDelta(k[0], k[1], k[2], new BigDecimal("+25"));
        assertThat(inv.getQty()).isEqualByComparingTo("75");
    }

    @Test
    @DisplayName("adjustDelta delta=0 抛 IllegalArgumentException")
    void adjustDelta_zeroRejected() {
        Long[] k = freshKey();
        assertThatThrownBy(() -> service.adjustDelta(k[0], k[1], k[2], BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("adjustDelta 使结果为负抛 InsufficientInventoryException")
    void adjustDelta_belowZeroThrows() {
        Long[] k = freshKey();
        service.addQty(k[0], k[1], k[2], new BigDecimal("10"));
        assertThatThrownBy(() -> service.adjustDelta(k[0], k[1], k[2], new BigDecimal("-20")))
                .isInstanceOf(InsufficientInventoryException.class);
    }

    // ====================================================================
    // Page
    // ====================================================================

    @Test
    @DisplayName("pageByWarehouse 返回该仓库下的库存分页")
    void pageByWarehouse_returnsPagedResults() {
        Long[] wh = freshKey();
        service.addQty(wh[0], freshKey()[1], freshKey()[2], new BigDecimal("10"));

        PageResult<WmsInventory> page = service.pageByWarehouse(wh[0], 1, 10);
        assertThat(page.getRecords()).isNotEmpty();
        assertThat(page.getRecords())
                .allMatch(inv -> wh[0].equals(inv.getWarehouseId()));
        assertThat(page.getPageNum()).isEqualTo(1);
        assertThat(page.getPageSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("pageByItem 返回该货品在所有仓库/库位的余额分页")
    void pageByItem_returnsPagedResults() {
        Long[] item = freshKey();
        service.addQty(freshKey()[0], freshKey()[1], item[0], new BigDecimal("5"));

        PageResult<WmsInventory> page = service.pageByItem(item[0], 1, 10);
        assertThat(page.getRecords()).isNotEmpty();
        assertThat(page.getRecords())
                .allMatch(inv -> item[0].equals(inv.getItemId()));
    }
}
