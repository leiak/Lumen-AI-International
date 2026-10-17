package com.lumen.finance.mapper;

import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.FinanceTestApplication;
import com.lumen.finance.entity.BillingRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.startupcheck.MinimumDurationRunningStartupCheckStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDate;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BillingRule Mapper smoke test (Iteration 4.3.1 PR1 T1.4).
 *
 * <p>目的：验证 V26 Flyway migration 创建的 t_billing_rule 表 schema 与
 * {@link BillingRule} entity 的 MyBatis-Plus 元数据完全对齐 — insert + selectById
 * 能 round-trip 回原始字段 (FieldFill 自动填充 created_at / updated_at /
 * deleted / version 都正常).
 *
 * <p>模式：Testcontainers MySQL + Flyway（src/test/resources/db/migration/V1
 * 是 V26 schema 的精简子集，不含 RBAC/t_state_transition seed） + TenantContext.
 */
@SpringBootTest(classes = FinanceTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class BillingRuleMapperTest {

    /**
     * 全 JVM 共享的 MySQL 容器 (与 lumen-wms MasterDataIT 同模式).
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
        // LocalDate.now() / MySQL CURDATE() 都在同一时区.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired
    private BillingRuleMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUpTenant() {
        TenantContext.set(1L);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void insertAndSelect_returnsInsertedRow() {
        BillingRule rule = new BillingRule();
        rule.setTenantId(1L);
        rule.setBusinessType(FinanceConstants.BIZ_LAST_MILE);
        rule.setDirection(FinanceConstants.DIR_RECEIVABLE);
        rule.setCounterpartyType(FinanceConstants.CP_CUSTOMER);
        rule.setCurrency(FinanceConstants.CURRENCY_CNY);
        rule.setPriority(100);
        rule.setStatus(FinanceConstants.RULE_STATUS_DRAFT);
        rule.setEffectiveFrom(LocalDate.now());

        mapper.insert(rule);
        assertNotNull(rule.getId());
        // MyMetaObjectHandler 在 insert 时填充 deleted=0 / version=0,
        // MyBatis-Plus 自动回填 id. 这些字段在 entity 上读到的值是 fill 后的.
        assertEquals(0, rule.getDeleted());
        assertEquals(0, rule.getVersion());

        // selectById: business_type round-trip 验证列名 / 类型对齐.
        // 注: @TableLogic select=false 让 deleted 不会出现在 SELECT 列表里,
        //     @Version 让 version 由 optimistic-lock 插件管理, selectById 也不
        //     会回填 — 因此 deleted/version 不在 found 上断言, 改走 JdbcTemplate
        //     直查 DB 验证 schema 默认值落库正确.
        BillingRule found = mapper.selectById(rule.getId());
        assertNotNull(found);
        assertEquals(FinanceConstants.BIZ_LAST_MILE, found.getBusinessType());

        // 直查 DB 验证 deleted=0 / version=0 落库 (schema 默认值正确).
        Integer dbDeleted = jdbc.queryForObject(
                "SELECT deleted FROM t_billing_rule WHERE id = ?", Integer.class, rule.getId());
        Integer dbVersion = jdbc.queryForObject(
                "SELECT version FROM t_billing_rule WHERE id = ?", Integer.class, rule.getId());
        assertEquals(0, dbDeleted);
        assertEquals(0, dbVersion);
    }
}