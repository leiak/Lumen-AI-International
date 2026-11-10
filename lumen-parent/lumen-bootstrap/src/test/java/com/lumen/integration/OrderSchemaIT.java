package com.lumen.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迭代 4.6 — V42 迁移落地断言。
 *
 * <p>六件事必须成立:
 * <ol>
 *   <li>flyway_schema_history 里有 V42 且 success=1 —— 证明 lumen-order 的
 *       src/main/resources/db/migration 被 bootstrap 扫到了 (locations: classpath:db/migration
 *       扫全 classpath, 与 lumen-cs 的 V24/V24.2/V24.3 同一机制)。</li>
 *   <li>三张新表存在且关键列齐 —— t_order / t_order_cargo / t_order_service_item。</li>
 *   <li>业务单号唯一键 uk_order_no 的列**恰好**是 (tenant_id, order_no) ——
 *       不带 deleted, 委托单号软删后永久占用, 不得复用。</li>
 *   <li>t_state_transition 里 state_machine_code='ORDER' 恰好 15 条, 转换对集合与
 *       V42 完全一致, tenant_id 全是 0 (全局行 —— 填 1 会让其它租户看不到转换规则)。</li>
 *   <li>状态机 guard_expression 引用的角色 code 在 sys_role 里真实存在。</li>
 *   <li>14 个权限码 / 3 个角色 / 27 行角色权限绑定, 集合与 V42 完全一致
 *       (SUPER_ADMIN 必须显式绑到 10 个 order:* 权限 —— 不绑 admin 登录后拿不到 order:*)。</li>
 * </ol>
 *
 * <p>断言方式说明: 计数 + GROUP_CONCAT 精确集合比对, **不用 LIKE 前缀计数** ——
 * 前缀计数遇到拼写错误也会通过 (比如把 order:view 写成 order:viwe, 数量还是 14)。
 */
class OrderSchemaIT extends IntegrationBase {

    @Autowired
    JdbcTemplate jdbc;

    /** V42 seed 的 14 个权限码, 按 id 升序 —— 与 V42 的 INSERT 顺序一致。 */
    private static final String EXPECTED_PERM_CODES =
            "order:view,order:create,order:edit,order:submit,order:confirm,order:cancel,"
                    + "order:void,order:operate,order:settle,order:close,"
                    + "customer:freight_order:view,customer:freight_order:create,"
                    + "customer:freight_order:confirm,customer:freight_order:cancel";

    /** V42 seed 的 3 个新角色 code, 按 id 升序。 */
    private static final String EXPECTED_ROLE_CODES = "order_sales,order_operator,order_supervisor";

    /** V42 seed 的 27 行 sys_role_permission, 按 (role_id, permission_id) 升序。 */
    private static final String EXPECTED_BINDINGS =
            "1-172,1-173,1-174,1-175,1-176,1-177,1-178,1-179,1-180,1-181,"
                    + "11-180,13-181,"
                    + "14-182,14-183,14-184,14-185,"
                    + "15-172,15-173,15-174,15-175,15-176,15-177,"
                    + "16-172,16-179,"
                    + "17-172,17-178,17-179";

    /** V42 seed 的 15 条状态转换, 按 sort_order 升序。 */
    private static final String EXPECTED_TRANSITIONS =
            "DRAFT>PENDING_CONFIRM,DRAFT>CANCELLED,DRAFT>VOID,"
                    + "PENDING_CONFIRM>CONFIRMED,PENDING_CONFIRM>DRAFT,"
                    + "PENDING_CONFIRM>CANCELLED,PENDING_CONFIRM>VOID,"
                    + "CONFIRMED>IN_OPERATION,CONFIRMED>CANCELLED,CONFIRMED>VOID,"
                    + "IN_OPERATION>DELIVERED,IN_OPERATION>CANCELLED,IN_OPERATION>VOID,"
                    + "DELIVERED>SETTLED,SETTLED>CLOSED";

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

        assertColumns("t_order",
                "order_no,business_type,customer_id,order_status,settlement_currency,version,deleted");
        assertColumns("t_order_cargo",
                "order_id,cargo_name_cn,hs_code,pkg_qty,weight_kg,volume_cbm,is_dangerous,version,deleted");
        assertColumns("t_order_service_item",
                "order_id,fee_type,amount,cost_amount,locked_amount,is_locked,version,deleted");
    }

    @Test
    @DisplayName("业务单号唯一键 uk_order_no 恰好是 (tenant_id, order_no) —— 不带 deleted")
    void uniqueKeyOnOrderNo() {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_order' AND INDEX_NAME = 'uk_order_no'",
                Integer.class);
        assertThat(cnt).as("uk_order_no 必须存在").isEqualTo(2);   // 两列各一行

        String cols = jdbc.queryForObject(
                "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') "
                        + "FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_order' AND INDEX_NAME = 'uk_order_no'",
                String.class);
        assertThat(cols).as("uk_order_no 的列集合 —— 单号软删后必须永久占用, 不能带 deleted")
                .isEqualTo("tenant_id,order_no");
    }

    @Test
    @DisplayName("ORDER 状态机 15 条转换与 V42 一致, 且 tenant_id 全为 0")
    void orderStateTransitionsSeeded() {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_state_transition WHERE state_machine_code = 'ORDER' AND deleted = 0",
                Integer.class);
        assertThat(cnt).isEqualTo(15);

        Integer bad = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_state_transition WHERE state_machine_code = 'ORDER' AND tenant_id <> 0",
                Integer.class);
        assertThat(bad).as("状态机 seed 必须是全局行 tenant_id=0").isZero();

        String pairs = jdbc.queryForObject(
                "SELECT GROUP_CONCAT(CONCAT(from_state, '>', to_state) ORDER BY sort_order, from_state, to_state SEPARATOR ',') "
                        + "FROM t_state_transition WHERE state_machine_code = 'ORDER' AND deleted = 0",
                String.class);
        assertThat(pairs).as("转换对集合必须与 V42 seed 完全一致").isEqualTo(EXPECTED_TRANSITIONS);
    }

    @Test
    @DisplayName("状态机 guard_expression 引用的角色 code 在 sys_role 里真实存在")
    void guardExpressionsReferenceRealRoles() {
        Integer bad = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_state_transition WHERE state_machine_code = 'ORDER' AND deleted = 0 "
                        + "AND (guard_expression IS NULL "
                        + "     OR guard_expression NOT IN (SELECT CONCAT('role:', code) FROM sys_role))",
                Integer.class);
        assertThat(bad).as("guard_expression 里的 role code 必须对得上 sys_role.code, 不能自造").isZero();
    }

    @Test
    @DisplayName("权限码 / 角色 / 绑定行集合与 V42 完全一致")
    void rbacSeeded() {
        String perms = jdbc.queryForObject(
                "SELECT GROUP_CONCAT(code ORDER BY id SEPARATOR ',') FROM sys_permission "
                        + "WHERE deleted = 0 AND (code LIKE 'order:%' OR code LIKE 'customer:freight_order:%')",
                String.class);
        assertThat(perms).as("14 个权限码的集合 —— 前缀计数抓不到拼写错误, 这里比对全集")
                .isEqualTo(EXPECTED_PERM_CODES);

        String roles = jdbc.queryForObject(
                "SELECT GROUP_CONCAT(code ORDER BY id SEPARATOR ',') FROM sys_role "
                        + "WHERE deleted = 0 AND code LIKE 'order_%'",
                String.class);
        assertThat(roles).as("3 个新角色的 code 集合").isEqualTo(EXPECTED_ROLE_CODES);

        // 27 行绑定: 覆盖「谁碰了本轮新权限 (172..185)」+「谁碰了本轮新角色 (15/16/17)」两个方向 ——
        // 只查其中一边会漏掉「新角色绑到旧权限」这种越权。
        String bindings = jdbc.queryForObject(
                "SELECT GROUP_CONCAT(CONCAT(role_id, '-', permission_id) ORDER BY role_id, permission_id SEPARATOR ',') "
                        + "FROM sys_role_permission "
                        + "WHERE permission_id BETWEEN 172 AND 185 OR role_id IN (15, 16, 17)",
                String.class);
        assertThat(bindings).as("27 行 sys_role_permission 的 (role_id, permission_id) 集合")
                .isEqualTo(EXPECTED_BINDINGS);

        // SUPER_ADMIN 必须显式绑到 10 个 order:* 权限 —— 不绑 admin 登录后拿不到 order:*
        Integer adminBindings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_role_permission rp JOIN sys_permission p ON p.id = rp.permission_id "
                        + "WHERE rp.role_id = 1 AND p.deleted = 0 AND p.code LIKE 'order:%'",
                Integer.class);
        assertThat(adminBindings).isEqualTo(10);
    }

    /**
     * 断言某张表**包含**期望的列 —— 少一列都算失败。
     *
     * <p>注意是「包含」不是「恰好等于」: {@code t_order} 有 59 列, 本断言只锁住
     * 本轮真正依赖的那些 (少一列 → 建单代码直接报 Unknown column)。
     *
     * @param table          表名
     * @param expectedCsv    期望列名, 逗号分隔
     */
    private void assertColumns(String table, String expectedCsv) {
        for (String col : expectedCsv.split(",")) {
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, col);
            assertThat(cnt).as("%s 应有列 %s", table, col).isEqualTo(1);
        }
    }
}
