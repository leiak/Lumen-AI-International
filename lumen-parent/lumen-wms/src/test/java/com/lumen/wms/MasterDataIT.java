package com.lumen.wms;

import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.wms.dto.CreateCustomerRequest;
import com.lumen.wms.dto.CreateItemRequest;
import com.lumen.wms.dto.CreateLocationRequest;
import com.lumen.wms.dto.CreateWarehouseRequest;
import com.lumen.wms.dto.UpdateCustomerRequest;
import com.lumen.wms.dto.UpdateItemRequest;
import com.lumen.wms.dto.UpdateLocationRequest;
import com.lumen.wms.dto.UpdateWarehouseRequest;
import com.lumen.wms.entity.WmsCustomer;
import com.lumen.wms.entity.WmsItem;
import com.lumen.wms.entity.WmsLocation;
import com.lumen.wms.entity.WmsWarehouse;
import com.lumen.wms.exception.CustomerNotFoundException;
import com.lumen.wms.exception.ItemNotFoundException;
import com.lumen.wms.exception.LocationNotFoundException;
import com.lumen.wms.exception.WarehouseNotFoundException;
import com.lumen.wms.exception.WmsDuplicateCodeException;
import com.lumen.wms.service.CustomerService;
import com.lumen.wms.service.ItemService;
import com.lumen.wms.service.LocationService;
import com.lumen.wms.service.WarehouseService;
import com.lumen.wms.support.WmsTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * WMS 4 个主数据服务（仓库 / 库位 / 货品 / 客户）的集成测试.
 *
 * <p>覆盖：
 * <ul>
 *   <li>正常 CRUD（create / getById / update / delete）</li>
 *   <li>唯一约束触发 {@link WmsDuplicateCodeException}</li>
 *   <li>id 不存在时各自抛 {@code *NotFoundException}</li>
 *   <li>{@code page(...)} 分页 + 模糊查询 / 多字段过滤</li>
 *   <li>库位按 {@code warehouseId} 过滤 + listByWarehouse 下拉</li>
 * </ul>
 *
 * <p>约定：
 * <ul>
 *   <li>每个测试都用 {@link WmsTestFixtures} 生成带 {@code nanoTime} 后缀的
 *       唯一 code/sku，避免跨用例撞 MySQL 唯一约束</li>
 *   <li>{@link TenantContext} 在每个测试前手动置 1L（与生产里登录后的 admin 租户一致），
 *       结束后清理（防止线程被 testcontainers 复用导致下一个 IT 看到脏 tenant）</li>
 *   <li>不用 {@code @Transactional} 包裹 — service 内部已经有 {@code @Transactional}，
 *       而且我们要验证的是真落库后的查询行为（包括 MyBatis-Plus 逻辑删除字段填充）</li>
 * </ul>
 *
 * <p>Testcontainer 启动一次后会被 failsafe fork 内的所有用例复用（static @Container
 * + {@code testcontainers.junit.jupiter.Testcontainers}），不用手工 {@code .stop()}.
 */
@SpringBootTest(classes = WmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("WMS 主数据服务集成测试")
class MasterDataIT {

    /**
     * 全 JVM 共享的 MySQL 容器.
     *
     * <p>{@code @Container} + {@code Testcontainers} 扩展会在 BeforeAll 阶段启动容器，
     * 并在 AfterAll 自动 stop. {@code static} 保证跨 IT 类复用.
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
        // 与 lumen-bootstrap IntegrationBase 保持一致：UTC 时区，
        // LocalDateTime.now() 与 MySQL NOW() 都在同一时区.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired WarehouseService warehouseService;
    @Autowired LocationService locationService;
    @Autowired ItemService itemService;
    @Autowired CustomerService customerService;

    /**
     * 测试租户 id.
     *
     * <p>不与 bootstrap 默认种子的 tenant=1 冲突即可（本测试不读 RBAC 数据，
     * 只依赖 MyBatis-Plus 多租户拦截器写入/读取 tenant_id 字段）.
     */
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
    // Warehouse
    // ====================================================================

    @Test
    @Order(1)
    @DisplayName("仓库：create + getById 正常落库")
    void warehouse_createAndGet() {
        CreateWarehouseRequest req = WmsTestFixtures.newWarehouse("WH", "主仓库");
        WmsWarehouse created = warehouseService.create(req);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getCode()).isEqualTo(req.getCode());
        assertThat(created.getName()).isEqualTo(req.getName());
        assertThat(created.getStatus()).isEqualTo("ACTIVE");
        assertThat(created.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(created.getDeleted()).isEqualTo(0);

        WmsWarehouse fetched = warehouseService.getById(created.getId());
        assertThat(fetched.getCode()).isEqualTo(req.getCode());
        assertThat(fetched.getName()).isEqualTo(req.getName());
    }

    @Test
    @Order(2)
    @DisplayName("仓库：update 修改 name/status 后 getById 返回新值")
    void warehouse_update() {
        CreateWarehouseRequest createReq = WmsTestFixtures.newWarehouse("WH_UPD", "原名");
        WmsWarehouse created = warehouseService.create(createReq);

        UpdateWarehouseRequest upd = WmsTestFixtures.updateWarehouse("新名字");
        WmsWarehouse updated = warehouseService.update(created.getId(), upd);

        assertThat(updated.getId()).isEqualTo(created.getId());
        assertThat(updated.getName()).isEqualTo("新名字");
        assertThat(warehouseService.getById(created.getId()).getName()).isEqualTo("新名字");
    }

    @Test
    @Order(3)
    @DisplayName("仓库：相同 code 重复 create 抛 WmsDuplicateCodeException")
    void warehouse_duplicateCodeRejected() {
        String dupCode = "WH_DUP_" + System.nanoTime();
        CreateWarehouseRequest first = WmsTestFixtures.newWarehouse("WH_DUP", "第一");
        first.setCode(dupCode);
        warehouseService.create(first);

        CreateWarehouseRequest second = WmsTestFixtures.newWarehouse("WH_DUP", "第二");
        second.setCode(dupCode);

        assertThatThrownBy(() -> warehouseService.create(second))
                .isInstanceOf(WmsDuplicateCodeException.class)
                .hasMessageContaining(dupCode);
    }

    @Test
    @Order(4)
    @DisplayName("仓库：getById 不存在 id 抛 WarehouseNotFoundException")
    void warehouse_notFound() {
        assertThatThrownBy(() -> warehouseService.getById(9_999_999L))
                .isInstanceOf(WarehouseNotFoundException.class);
    }

    @Test
    @Order(5)
    @DisplayName("仓库：delete 后再 getById 抛 NotFound")
    void warehouse_delete() {
        CreateWarehouseRequest req = WmsTestFixtures.newWarehouse("WH_DEL", "待删除");
        WmsWarehouse created = warehouseService.create(req);

        warehouseService.delete(created.getId());

        assertThatThrownBy(() -> warehouseService.getById(created.getId()))
                .isInstanceOf(WarehouseNotFoundException.class);
    }

    @Test
    @Order(6)
    @DisplayName("仓库：page 按 keyword 模糊匹配 name/code")
    void warehouse_pageWithKeyword() {
        // 用短 marker 当 code 前缀，避免超过 VARCHAR(32)
        String marker = "kw" + (System.nanoTime() % 1_000_000);
        CreateWarehouseRequest a = WmsTestFixtures.newWarehouse(marker, "keyword-a");
        CreateWarehouseRequest b = WmsTestFixtures.newWarehouse(marker, "keyword-b");
        warehouseService.create(a);
        warehouseService.create(b);
        // 再加一个无关仓库，确保不被 keyword 命中
        warehouseService.create(WmsTestFixtures.newWarehouse("OTHER", "other"));

        PageResult<WmsWarehouse> page = warehouseService.page(1L, 20L, marker, null);

        assertThat(page.getTotal()).isGreaterThanOrEqualTo(2);
        assertThat(page.getRecords())
                .extracting(WmsWarehouse::getCode)
                .allMatch(c -> c.contains(marker));
    }

    @Test
    @Order(7)
    @DisplayName("仓库：page 按 status 精确过滤")
    void warehouse_pageWithStatus() {
        // 默认 status='ACTIVE' 的仓库数
        PageResult<WmsWarehouse> active = warehouseService.page(1L, 100L, null, "ACTIVE");
        assertThat(active.getTotal()).isPositive();
        assertThat(active.getRecords())
                .extracting(WmsWarehouse::getStatus)
                .containsOnly("ACTIVE");

        // status='DISABLED' 在本组 IT 中没人造，应为空
        PageResult<WmsWarehouse> disabled = warehouseService.page(1L, 100L, null, "DISABLED");
        assertThat(disabled.getTotal()).isEqualTo(0L);
        assertThat(disabled.getRecords()).isEmpty();
    }

    // ====================================================================
    // Location
    // ====================================================================

    @Test
    @Order(10)
    @DisplayName("库位：create + getById 正常落库")
    void location_createAndGet() {
        Long warehouseId = createWarehouse();
        CreateLocationRequest req = WmsTestFixtures.newLocation(warehouseId, "LOC");
        WmsLocation created = locationService.create(req);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getWarehouseId()).isEqualTo(warehouseId);
        assertThat(created.getCode()).isEqualTo(req.getCode());
        assertThat(created.getZone()).isEqualTo("A区");
        assertThat(created.getCapacity()).isEqualByComparingTo(new BigDecimal("100.0000"));

        WmsLocation fetched = locationService.getById(created.getId());
        assertThat(fetched.getWarehouseId()).isEqualTo(warehouseId);
    }

    @Test
    @Order(11)
    @DisplayName("库位：update 修改 zone/status 后 getById 返回新值")
    void location_update() {
        Long warehouseId = createWarehouse();
        CreateLocationRequest createReq = WmsTestFixtures.newLocation(warehouseId, "LOC_UPD");
        WmsLocation created = locationService.create(createReq);

        UpdateLocationRequest upd = WmsTestFixtures.updateLocation("B区");
        WmsLocation updated = locationService.update(created.getId(), upd);

        assertThat(updated.getId()).isEqualTo(created.getId());
        assertThat(updated.getZone()).isEqualTo("B区");
        assertThat(locationService.getById(created.getId()).getZone()).isEqualTo("B区");
    }

    @Test
    @Order(12)
    @DisplayName("库位：同一仓库下重复 code 抛 WmsDuplicateCodeException")
    void location_duplicateCodeRejected() {
        Long warehouseId = createWarehouse();
        String dupCode = "LOC_DUP_" + System.nanoTime();

        CreateLocationRequest first = WmsTestFixtures.newLocation(warehouseId, "LOC_DUP");
        first.setCode(dupCode);
        locationService.create(first);

        CreateLocationRequest second = WmsTestFixtures.newLocation(warehouseId, "LOC_DUP");
        second.setCode(dupCode);

        assertThatThrownBy(() -> locationService.create(second))
                .isInstanceOf(WmsDuplicateCodeException.class);
    }

    @Test
    @Order(13)
    @DisplayName("库位：getById 不存在 id 抛 LocationNotFoundException")
    void location_notFound() {
        assertThatThrownBy(() -> locationService.getById(9_999_999L))
                .isInstanceOf(LocationNotFoundException.class);
    }

    @Test
    @Order(14)
    @DisplayName("库位：page 按 warehouseId 过滤只返回该仓库的库位")
    void location_pageByWarehouse() {
        Long wh1 = createWarehouse();
        Long wh2 = createWarehouse();
        locationService.create(WmsTestFixtures.newLocation(wh1, "PAGE_W1_A"));
        locationService.create(WmsTestFixtures.newLocation(wh1, "PAGE_W1_B"));
        locationService.create(WmsTestFixtures.newLocation(wh2, "PAGE_W2"));

        PageResult<WmsLocation> page = locationService.page(1L, 50L, wh1, null, null);

        assertThat(page.getRecords())
                .extracting(WmsLocation::getWarehouseId)
                .containsOnly(wh1);
        assertThat(page.getTotal()).isGreaterThanOrEqualTo(2L);
    }

    @Test
    @Order(15)
    @DisplayName("库位：listByWarehouse 返回该仓库全部库位，按 code 升序")
    void location_listByWarehouse() {
        Long warehouseId = createWarehouse();
        locationService.create(WmsTestFixtures.newLocation(warehouseId, "LIST_A"));
        locationService.create(WmsTestFixtures.newLocation(warehouseId, "LIST_B"));
        locationService.create(WmsTestFixtures.newLocation(warehouseId, "LIST_C"));

        var locations = locationService.listByWarehouse(warehouseId);

        assertThat(locations).hasSizeGreaterThanOrEqualTo(3);
        assertThat(locations)
                .extracting(WmsLocation::getWarehouseId)
                .containsOnly(warehouseId);
        // 升序断言：相邻元素 code 比较
        var codes = locations.stream().map(WmsLocation::getCode).toList();
        assertThat(codes).isSorted();
    }

    // ====================================================================
    // Item
    // ====================================================================

    @Test
    @Order(20)
    @DisplayName("货品：create + getById 正常落库，sku/uom 必填字段保留")
    void item_createAndGet() {
        CreateItemRequest req = WmsTestFixtures.newItem("SKU", "货品");
        WmsItem created = itemService.create(req);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getSku()).isEqualTo(req.getSku());
        assertThat(created.getName()).isEqualTo(req.getName());
        assertThat(created.getUom()).isEqualTo("EA");
        assertThat(created.getUnitCost()).isEqualByComparingTo(new BigDecimal("9.9900"));

        WmsItem fetched = itemService.getById(created.getId());
        assertThat(fetched.getSku()).isEqualTo(req.getSku());
        assertThat(fetched.getBarcode()).isEqualTo(req.getBarcode());
    }

    @Test
    @Order(21)
    @DisplayName("货品：update 修改 name/unitCost 后 getById 返回新值")
    void item_update() {
        CreateItemRequest createReq = WmsTestFixtures.newItem("SKU_UPD", "原货品");
        WmsItem created = itemService.create(createReq);

        UpdateItemRequest upd = WmsTestFixtures.updateItem("新货品");
        WmsItem updated = itemService.update(created.getId(), upd);

        assertThat(updated.getId()).isEqualTo(created.getId());
        assertThat(updated.getName()).isEqualTo("新货品");
        assertThat(updated.getUnitCost()).isEqualByComparingTo(new BigDecimal("19.9900"));
    }

    @Test
    @Order(22)
    @DisplayName("货品：相同 sku 重复 create 抛 WmsDuplicateCodeException")
    void item_duplicateCodeRejected() {
        String dupSku = "SKU_DUP_" + System.nanoTime();
        CreateItemRequest first = WmsTestFixtures.newItem("SKU_DUP", "第一");
        first.setSku(dupSku);
        itemService.create(first);

        CreateItemRequest second = WmsTestFixtures.newItem("SKU_DUP", "第二");
        second.setSku(dupSku);

        assertThatThrownBy(() -> itemService.create(second))
                .isInstanceOf(WmsDuplicateCodeException.class)
                .hasMessageContaining(dupSku);
    }

    @Test
    @Order(23)
    @DisplayName("货品：getById 不存在 id 抛 ItemNotFoundException")
    void item_notFound() {
        assertThatThrownBy(() -> itemService.getById(9_999_999L))
                .isInstanceOf(ItemNotFoundException.class);
    }

    @Test
    @Order(24)
    @DisplayName("货品：page 按 keyword 模糊匹配 sku/name/barcode")
    void item_pageWithKeyword() {
        // 用短 marker 当 sku 前缀，sku 列是 VARCHAR(64)，留点余量
        String marker = "kw" + (System.nanoTime() % 1_000_000);
        CreateItemRequest a = WmsTestFixtures.newItem(marker, "kw-a");
        CreateItemRequest b = WmsTestFixtures.newItem(marker, "kw-b");
        itemService.create(a);
        itemService.create(b);
        itemService.create(WmsTestFixtures.newItem("OTHER", "other-item"));

        PageResult<WmsItem> page = itemService.page(1L, 50L, marker);

        assertThat(page.getTotal()).isGreaterThanOrEqualTo(2);
        assertThat(page.getRecords())
                .extracting(WmsItem::getSku)
                .allMatch(s -> s.contains(marker));
    }

    // ====================================================================
    // Customer
    // ====================================================================

    @Test
    @Order(30)
    @DisplayName("客户：create + getById 正常落库")
    void customer_createAndGet() {
        CreateCustomerRequest req = WmsTestFixtures.newCustomer("CUS", "客户");
        WmsCustomer created = customerService.create(req);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getCode()).isEqualTo(req.getCode());
        assertThat(created.getName()).isEqualTo(req.getName());
        assertThat(created.getContact()).isEqualTo("联系人");
        assertThat(created.getPhone()).isEqualTo("13800000000");

        WmsCustomer fetched = customerService.getById(created.getId());
        assertThat(fetched.getCode()).isEqualTo(req.getCode());
        assertThat(fetched.getAddress()).isEqualTo("客户地址");
    }

    @Test
    @Order(31)
    @DisplayName("客户：update 修改 name/contact 后 getById 返回新值")
    void customer_update() {
        CreateCustomerRequest createReq = WmsTestFixtures.newCustomer("CUS_UPD", "原客户");
        WmsCustomer created = customerService.create(createReq);

        UpdateCustomerRequest upd = WmsTestFixtures.updateCustomer("新客户");
        WmsCustomer updated = customerService.update(created.getId(), upd);

        assertThat(updated.getId()).isEqualTo(created.getId());
        assertThat(updated.getName()).isEqualTo("新客户");
        assertThat(updated.getContact()).isEqualTo("新联系人");
    }

    @Test
    @Order(32)
    @DisplayName("客户：相同 code 重复 create 抛 WmsDuplicateCodeException")
    void customer_duplicateCodeRejected() {
        String dupCode = "CUS_DUP_" + System.nanoTime();
        CreateCustomerRequest first = WmsTestFixtures.newCustomer("CUS_DUP", "第一");
        first.setCode(dupCode);
        customerService.create(first);

        CreateCustomerRequest second = WmsTestFixtures.newCustomer("CUS_DUP", "第二");
        second.setCode(dupCode);

        assertThatThrownBy(() -> customerService.create(second))
                .isInstanceOf(WmsDuplicateCodeException.class)
                .hasMessageContaining(dupCode);
    }

    @Test
    @Order(33)
    @DisplayName("客户：getById 不存在 id 抛 CustomerNotFoundException")
    void customer_notFound() {
        assertThatThrownBy(() -> customerService.getById(9_999_999L))
                .isInstanceOf(CustomerNotFoundException.class);
    }

    @Test
    @Order(34)
    @DisplayName("客户：page 按 keyword 模糊匹配 name/code")
    void customer_pageWithKeyword() {
        // 用短 marker 当 code 前缀，避免超过 VARCHAR(32)
        String marker = "kw" + (System.nanoTime() % 1_000_000);
        CreateCustomerRequest a = WmsTestFixtures.newCustomer(marker, "kw-a");
        CreateCustomerRequest b = WmsTestFixtures.newCustomer(marker, "kw-b");
        customerService.create(a);
        customerService.create(b);
        customerService.create(WmsTestFixtures.newCustomer("OTHER", "other-customer"));

        PageResult<WmsCustomer> page = customerService.page(1L, 50L, marker);

        assertThat(page.getTotal()).isGreaterThanOrEqualTo(2);
        assertThat(page.getRecords())
                .extracting(WmsCustomer::getCode)
                .allMatch(c -> c.contains(marker));
    }

    // ====================================================================
    // helpers
    // ====================================================================

    /**
     * 创建一个独立的 warehouse 给库位测试当 FK 目标.
     *
     * <p>不共享 fixture 是因为每次 create 都会触发 INSERT，跑多次 service.create
     * 比直接 cache 一个共享的 warehouse 更有"端到端"覆盖意义.
     */
    private Long createWarehouse() {
        CreateWarehouseRequest req = WmsTestFixtures.newWarehouse("WH_HELPER", "helper");
        return warehouseService.create(req).getId();
    }
}