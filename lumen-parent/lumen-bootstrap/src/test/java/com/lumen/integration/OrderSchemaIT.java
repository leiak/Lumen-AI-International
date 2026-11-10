package com.lumen.integration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lumen.order.OrderConstants;
import com.lumen.order.entity.Order;
import com.lumen.order.entity.OrderCargo;
import com.lumen.order.entity.OrderServiceItem;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迭代 4.6 — V42 迁移落地断言。
 *
 * <p>八件事必须成立:
 * <ol>
 *   <li>flyway_schema_history 里有 V42 且 success=1 —— 证明 lumen-order 的
 *       src/main/resources/db/migration 被 bootstrap 扫到了 (locations: classpath:db/migration
 *       扫全 classpath, 与 lumen-cs 的 V24/V24.2/V24.3 同一机制)。</li>
 *   <li>三张新表存在且关键列齐 —— t_order / t_order_cargo / t_order_service_item。</li>
 *   <li>业务单号唯一键 uk_order_no 的列**恰好**是 (tenant_id, order_no) ——
 *       不带 deleted, 委托单号软删后永久占用, 不得复用。</li>
 *   <li>t_state_transition 里 state_machine_code='ORDER' 恰好 15 条, 转换对集合与
 *       V42 完全一致, tenant_id 全是 0 (全局行 —— 填 1 会让其它租户看不到转换规则)。</li>
 *   <li>DB 的 15 条转换与 Java 侧镜像 {@code OrderConstants.TRANSITIONS} **逐行一致**
 *       —— 两侧不再各说各话 (改了一边另一边会红)。</li>
 *   <li>状态机 guard_expression 引用的角色 code 在 sys_role 里真实存在。</li>
 *   <li>14 个权限码 / 3 个角色 / 27 行角色权限绑定, 集合与 V42 完全一致
 *       (SUPER_ADMIN 必须显式绑到 10 个 order:* 权限 —— 不绑 admin 登录后拿不到 order:*)。</li>
 *   <li>三张表的**真实列集** (information_schema) 与**实体推导列集**相等 —— 闭环。</li>
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

    /** V42 seed 的 15 条状态转换 (from>to@guard_expression), 按 sort_order 升序。 */
    private static final String EXPECTED_TRANSITIONS =
            "DRAFT>PENDING_CONFIRM@role:order_sales,DRAFT>CANCELLED@role:order_sales,DRAFT>VOID@role:order_supervisor,"
                    + "PENDING_CONFIRM>CONFIRMED@role:customer,PENDING_CONFIRM>DRAFT@role:order_sales,"
                    + "PENDING_CONFIRM>CANCELLED@role:order_sales,PENDING_CONFIRM>VOID@role:order_supervisor,"
                    + "CONFIRMED>IN_OPERATION@role:order_operator,CONFIRMED>CANCELLED@role:order_supervisor,"
                    + "CONFIRMED>VOID@role:order_supervisor,"
                    + "IN_OPERATION>DELIVERED@role:order_operator,IN_OPERATION>CANCELLED@role:order_supervisor,"
                    + "IN_OPERATION>VOID@role:order_supervisor,"
                    + "DELIVERED>SETTLED@role:finance_ops,SETTLED>CLOSED@role:finance_manager";

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
        // NON_UNIQUE = 0 —— 光断言索引名和列集合不够: 有人把 UNIQUE KEY 改成普通 KEY,
        // 这两条照样绿, 而「单号唯一」正是这个索引的全部意义。
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_order' "
                        + "AND INDEX_NAME = 'uk_order_no' AND NON_UNIQUE = 0",
                Integer.class);
        assertThat(cnt).as("uk_order_no 必须是唯一索引").isEqualTo(2);   // 两列各一行

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
                "SELECT GROUP_CONCAT(CONCAT(from_state, '>', to_state, '@', guard_expression) "
                        + "ORDER BY sort_order, from_state, to_state SEPARATOR ',') "
                        + "FROM t_state_transition WHERE state_machine_code = 'ORDER' AND deleted = 0",
                String.class);
        assertThat(pairs).as("转换对 + guard_expression 必须与 V42 seed 完全一致").isEqualTo(EXPECTED_TRANSITIONS);
    }

    @Test
    @DisplayName("状态机 guard_expression 引用的角色 code 在 sys_role 里真实存在")
    void guardExpressionsReferenceRealRoles() {
        Integer bad = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_state_transition WHERE state_machine_code = 'ORDER' AND deleted = 0 "
                        + "AND (guard_expression IS NULL "
                        + "     OR guard_expression NOT IN "
                        + "         (SELECT CONCAT('role:', code) FROM sys_role WHERE deleted = 0))",
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
                        + "WHERE deleted = 0 AND code LIKE 'order\\_%'",
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
     * DB 的 15 条 ORDER 转换与 Java 侧镜像 {@code OrderConstants.TRANSITIONS} 逐行一致。
     *
     * <p><b>为什么必须有这道断言。</b> 状态机在 DB 侧 ({@code t_state_transition} seed)
     * 与 Java 侧 ({@code OrderConstants.TRANSITIONS}) 各有一份, 两份互不校验时,
     * 改了 V43 的 seed 而忘了改 Java 侧, 或者反过来 —— 没有任何测试会红,
     * 直到线上出现一条「表里有但代码里没有」的转换。
     *
     * <p>断言用 {@code containsExactlyElementsOf}: 顺序敏感 + 元素逐个相等,
     * 因此「少一行」「多一行」「顺序不同」「值不同」四种偏差都会红 —— 只比 count 抓不到后两种。
     */
    @Test
    @DisplayName("DB 的 15 条 ORDER 转换与 OrderConstants.TRANSITIONS 逐行一致")
    void orderStateTransitionsMatchJavaMirror() {
        List<String> dbRows = jdbc.queryForList(
                "SELECT CONCAT(from_state, '>', to_state, '@', transition_name) "
                        + "FROM t_state_transition "
                        + "WHERE state_machine_code = 'ORDER' AND tenant_id = 0 AND deleted = 0 "
                        + "ORDER BY sort_order",
                String.class);
        List<String> javaRows = OrderConstants.TRANSITIONS.stream()
                .map(t -> t.from() + ">" + t.to() + "@" + t.action())
                .toList();

        assertThat(dbRows)
                .as("DB 的转换序列 (from>to@action, 按 sort_order) 必须与 Java 侧镜像逐行一致 —— "
                        + "少一行 / 多一行 / 顺序不同 / 值不同 都要红")
                .containsExactlyElementsOf(javaRows);
    }

    /**
     * 三张表的**真实列集**与**实体推导列集**相等 —— 这才是「实体 ↔ 表」的真正闭环。
     *
     * <p>lumen-order 里的 {@code OrderEntityColumnMappingTest} 比的是「实体推导列集 vs
     * 测试里手写的一份列名快照」, 并不读 DDL 也不读真实表; 本测试查
     * {@code information_schema.COLUMNS} 拿建表后的真实列集, 才把这条链闭上。
     *
     * <p>断言用「集合相等 (双向)」而不是只比数量 —— 多一列 / 少一列都会红。
     */
    @Test
    @DisplayName("三张表的真实列集与实体推导列集相等")
    void entityColumnsMatchRealTables() {
        assertColumnSetsEqual("t_order", Order.class);
        assertColumnSetsEqual("t_order_cargo", OrderCargo.class);
        assertColumnSetsEqual("t_order_service_item", OrderServiceItem.class);
    }

    /**
     * 断言某张表的真实列集 (information_schema) 与实体推导列集相等。
     *
     * @param table  表名
     * @param entity 对应的实体类
     */
    private void assertColumnSetsEqual(String table, Class<?> entity) {
        List<String> realColumns = jdbc.queryForList(
                "SELECT LOWER(COLUMN_NAME) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                String.class, table);
        List<String> entityColumns = columnsOf(entity);

        // containsExactlyInAnyOrderElementsOf 本身就是「双向 + 元素多重集相等」:
        // 多一列 / 少一列 / 列名对不上, 三种偏差都会红。
        assertThat(realColumns)
                .as("%s 的真实列集 (information_schema) 必须与实体推导列集完全相等", table)
                .containsExactlyInAnyOrderElementsOf(entityColumns);
    }

    /**
     * 取实体在 MyBatis-Plus 下真实推导出的列名 (主键列 + 全部字段列)。
     *
     * <p>刻意用 {@link MybatisConfiguration} 而不是裸的 {@code Configuration}:
     * 前者的构造器把 {@code mapUnderscoreToCamelCase} 置为 true, 与
     * {@code MybatisPlusAutoConfiguration} 在 Spring 容器里创建的实例口径一致
     * (它同样是 {@code new MybatisConfiguration()})。用裸 Configuration 会推出一套
     * 「驼峰原样」的列名, 与线上不符 —— 这道测试就白测了。
     */
    private static List<String> columnsOf(Class<?> entity) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace(entity.getName());
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, entity);
        List<String> columns = new ArrayList<>();
        columns.add(tableInfo.getKeyColumn());
        for (TableFieldInfo field : tableInfo.getFieldList()) {
            columns.add(field.getColumn());
        }
        return columns;
    }

    /**
     * 断言某张表**包含**期望的列 —— 少一列都算失败。
     *
     * <p>注意是「包含」不是「恰好等于」: {@code t_order} 有 54 列, 本断言只锁住
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
