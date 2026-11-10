package com.lumen.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迭代 4.6 — V42 迁移落地断言。
 *
 * <p>三件事必须成立:
 * <ol>
 *   <li>flyway_schema_history 里有 V42 且 success=1 —— 证明 lumen-order 的
 *       src/main/resources/db/migration 被 bootstrap 扫到了 (locations: classpath:db/migration
 *       扫全 classpath, 与 lumen-cs 的 V24/V24.2/V24.3 同一机制)。</li>
 *   <li>三张新表存在且列齐 —— t_order / t_order_cargo / t_order_service_item。</li>
 *   <li>t_state_transition 里 state_machine_code='ORDER' 恰好 15 行, 且 tenant_id 全是 0
 *       (全局行 —— 填 1 会让其它租户看不到转换规则)。</li>
 * </ol>
 */
class OrderSchemaIT extends IntegrationBase {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("V42 已应用: flyway_schema_history 有 success 记录")
    void v42_appliedSuccessfully() {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '42' AND success = 1",
                Integer.class);
        assertThat(cnt).isEqualTo(1);
    }

    @Test
    @DisplayName("三张新表存在且关键列齐")
    void threeTablesExist() {
        for (String t : new String[]{"t_order", "t_order_cargo", "t_order_service_item"}) {
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                    Integer.class, t);
            assertThat(cnt).as("表 %s 应存在", t).isEqualTo(1);
        }

        Integer cols = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 't_order' AND COLUMN_NAME IN "
                        + "('order_no','business_type','customer_id','order_status','settlement_currency','version','deleted')",
                Integer.class);
        assertThat(cols).isEqualTo(7);
    }

    @Test
    @DisplayName("ORDER 状态机 seed 15 行且 tenant_id 全为 0")
    void orderStateTransitionsSeeded() {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_state_transition WHERE state_machine_code = 'ORDER' AND deleted = 0",
                Integer.class);
        assertThat(cnt).isEqualTo(15);

        Integer bad = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_state_transition WHERE state_machine_code = 'ORDER' AND tenant_id <> 0",
                Integer.class);
        assertThat(bad).as("状态机 seed 必须是全局行 tenant_id=0").isZero();
    }

    @Test
    @DisplayName("权限码 / 角色 / 绑定行数正确")
    void rbacSeeded() {
        Integer perms = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_permission WHERE code LIKE 'order:%' OR code LIKE 'customer:freight_order:%'",
                Integer.class);
        assertThat(perms).isEqualTo(14);

        Integer roles = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_role WHERE code IN ('order_sales','order_operator','order_supervisor')",
                Integer.class);
        assertThat(roles).isEqualTo(3);

        // SUPER_ADMIN 必须显式绑到 10 个 order:* 权限 —— 不绑 admin 登录后拿不到 order:*
        Integer adminBindings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_role_permission rp JOIN sys_permission p ON p.id = rp.permission_id "
                        + "WHERE rp.role_id = 1 AND p.code LIKE 'order:%'",
                Integer.class);
        assertThat(adminBindings).isEqualTo(10);
    }
}
