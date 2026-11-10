package com.lumen.order.entity;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三个实体的「字段 → 列名」推导结果必须与本文件里的 **V42 列清单快照** 一一对应 (Iteration 4.6).
 *
 * <p><b>为什么要锁这一层。</b> MyBatis-Plus 按驼峰转下划线推导列名
 * ({@code MybatisConfiguration} 构造器里把 {@code mapUnderscoreToCamelCase} 置为 true,
 * 故 {@code isDangerous → is_dangerous} / {@code unNo → un_no} 这类都能自动对上)。
 * 推导对了就没有必要逐列写 {@code @TableField} —— 本仓既有实体
 * ({@code WmsDeliveryOrder} 等) 也都是这个口径。
 *
 * <p>但推导是<b>静默</b>的: 字段名手滑写错一个字母 (如 {@code pkgQty} 写成
 * {@code packageQty}), 编译期不报错, 只有真到运行时 INSERT 才炸 "Unknown column"。
 * 这道测试把「实体字段集 == 列清单」变成编译后立刻可见的断言。
 *
 * <p><b>本测试比的是「实体推导列集 vs 本文件里手写的列名快照」</b> —— 它<b>不读</b>
 * {@code V42__order_schema.sql}, 也不是建表后的真实列集。
 * 真正与真实表列集闭环的那道断言在 bootstrap 的
 * {@code OrderSchemaIT} 里 (查 {@code information_schema.COLUMNS} 与实体推导列集比对),
 * 两道测试各管一段, 不要混淆。
 */
class OrderEntityColumnMappingTest {

    @Test
    @DisplayName("t_order 实体推导的列名与 V42 列清单快照一致")
    void orderColumnsMatchV42() {
        assertThat(columnsOf(Order.class)).containsExactlyInAnyOrder(
                "id",
                "tenant_id", "created_at", "updated_at", "deleted", "version",
                "order_no", "business_type", "customer_id", "customer_contract_id",
                "sales_id", "operator_id", "trade_direction", "incoterms", "service_type",
                "origin_port_id", "dest_port_id", "origin_address", "dest_address",
                "pickup_warehouse_id", "delivery_warehouse_id",
                "cargo_ready_date", "etd", "eta",
                "total_qty", "total_weight_kg", "total_volume_cbm",
                "is_dangerous", "is_oversized", "temperature_range",
                "container_qty", "container_type_code", "marks_and_nos", "cargo_description",
                "order_status", "settlement_currency", "exchange_rate_type", "locked_exchange_rate",
                "is_locked", "lock_at", "lock_by",
                "inquiry_id", "quotation_id",
                "cancel_reason", "close_reason", "void_reason",
                "submitted_at", "confirmed_at", "delivered_at", "settled_at", "closed_at",
                "remark", "created_by", "updated_by");
    }

    @Test
    @DisplayName("t_order_cargo 实体推导的列名与 V42 列清单快照一致")
    void orderCargoColumnsMatchV42() {
        assertThat(columnsOf(OrderCargo.class)).containsExactlyInAnyOrder(
                "id",
                "tenant_id", "created_at", "updated_at", "deleted", "version",
                "order_id",
                "cargo_name_cn", "cargo_name_en", "hs_code", "pkg_type", "pkg_qty", "unit",
                "weight_kg", "net_weight_kg", "volume_cbm",
                "length_cm", "width_cm", "height_cm",
                "is_stackable", "cargo_value", "currency_code",
                "brand_name", "model", "is_dangerous",
                "un_no", "danger_class", "remark");
    }

    @Test
    @DisplayName("t_order_service_item 实体推导的列名与 V42 列清单快照一致")
    void orderServiceItemColumnsMatchV42() {
        assertThat(columnsOf(OrderServiceItem.class)).containsExactlyInAnyOrder(
                "id",
                "tenant_id", "created_at", "updated_at", "deleted", "version",
                "order_id",
                "fee_type", "fee_name", "fee_category", "calc_basis",
                "qty", "unit_price", "amount", "currency_code",
                "supplier_id", "cost_amount", "locked_amount",
                "is_locked", "is_mandatory", "source", "remark");
    }

    /**
     * 取实体在 MyBatis-Plus 下真实推导出的列名 (主键列 + 全部字段列)。
     *
     * <p>刻意用 {@link MybatisConfiguration} 而不是裸的 {@code Configuration}:
     * 前者的构造器把 {@code mapUnderscoreToCamelCase} 置为 true, 与
     * {@code MybatisPlusAutoConfiguration} 在 Spring 容器里创建的实例口径一致
     * (它同样是 {@code new MybatisConfiguration()})。用裸 Configuration 会得到
     * 一套「驼峰原样」的列名, 与线上不符 —— 这道测试就白测了。
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
}
