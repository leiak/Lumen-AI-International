package com.lumen.wms.support;

import com.lumen.wms.dto.CreateCustomerRequest;
import com.lumen.wms.dto.CreateItemRequest;
import com.lumen.wms.dto.CreateLocationRequest;
import com.lumen.wms.dto.CreateWarehouseRequest;
import com.lumen.wms.dto.UpdateCustomerRequest;
import com.lumen.wms.dto.UpdateItemRequest;
import com.lumen.wms.dto.UpdateLocationRequest;
import com.lumen.wms.dto.UpdateWarehouseRequest;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WMS 主数据测试数据工厂.
 *
 * <p>每个 helper 都会在传入的 {@code prefix} 后面追加递增序号，保证同一 JVM
 * 内跨测试方法（即使跑多次或重跑）业务 code 都不会撞 MySQL 唯一约束
 * {@code (tenant_id, code)}. 实际场景里这个序列号也是 IT 失败的常见来源.
 *
 * <p>注意 MySQL schema 对字段长度的约束：
 * <ul>
 *   <li>{@code wms_warehouse.code} / {@code wms_location.code} / {@code wms_customer.code}
 *       都是 VARCHAR(32)，因此总长度 ≤ 32</li>
 *   <li>{@code wms_item.sku} 是 VARCHAR(64)，可稍长</li>
 * </ul>
 * 每个 helper 的 prefix 都预留了 1~8 个字符预算，序号用 8 位整数（够跑 99999999 次）.
 *
 * <p>只放"无副作用的请求对象构造"逻辑；持久化由各测试方法直接调用 service 完成，
 * 这样断言路径与生产代码一致.
 */
public final class WmsTestFixtures {

    private static final AtomicLong SEQ = new AtomicLong(0);

    private WmsTestFixtures() {}

    /**
     * 生成长度可控的唯一后缀：用 {@code nanoTime()} 后 8 位 + 自增序号，
     * 不直接拼 nanoTime 整数值（19 位超 VARCHAR(32)).
     */
    private static String unique(String prefix) {
        // (last 8 digits of nanoTime) 最多 8 位；SEQ 单测内单调递增，永远不撞
        long nanos = System.nanoTime();
        String suffix = Long.toString(nanos).substring(Math.max(0, Long.toString(nanos).length() - 8));
        return prefix + suffix + SEQ.incrementAndGet();
    }

    // ---------- Warehouse ----------

    public static CreateWarehouseRequest newWarehouse(String codePrefix, String namePrefix) {
        CreateWarehouseRequest req = new CreateWarehouseRequest();
        req.setCode(unique(codePrefix));
        req.setName(namePrefix + "-warehouse");
        req.setType("SELF");
        req.setStatus("ACTIVE");
        req.setAddress("测试地址");
        return req;
    }

    public static UpdateWarehouseRequest updateWarehouse(String newName) {
        UpdateWarehouseRequest req = new UpdateWarehouseRequest();
        req.setName(newName);
        req.setStatus("ACTIVE");
        return req;
    }

    // ---------- Location ----------

    public static CreateLocationRequest newLocation(Long warehouseId, String codePrefix) {
        CreateLocationRequest req = new CreateLocationRequest();
        req.setWarehouseId(warehouseId);
        req.setCode(unique(codePrefix));
        req.setZone("A区");
        req.setCapacity(new BigDecimal("100.0000"));
        req.setStatus("ACTIVE");
        return req;
    }

    public static UpdateLocationRequest updateLocation(String newZone) {
        UpdateLocationRequest req = new UpdateLocationRequest();
        req.setZone(newZone);
        req.setStatus("ACTIVE");
        return req;
    }

    // ---------- Item (sku VARCHAR(64)，可稍长) ----------

    public static CreateItemRequest newItem(String skuPrefix, String namePrefix) {
        CreateItemRequest req = new CreateItemRequest();
        req.setSku(unique(skuPrefix));
        req.setName(namePrefix + "-item");
        req.setUom("EA");
        req.setBarcode("BC" + System.nanoTime());
        req.setHazardClass("NONE");
        req.setUnitCost(new BigDecimal("9.9900"));
        return req;
    }

    public static UpdateItemRequest updateItem(String newName) {
        UpdateItemRequest req = new UpdateItemRequest();
        req.setName(newName);
        req.setUnitCost(new BigDecimal("19.9900"));
        return req;
    }

    // ---------- Customer ----------

    public static CreateCustomerRequest newCustomer(String codePrefix, String namePrefix) {
        CreateCustomerRequest req = new CreateCustomerRequest();
        req.setCode(unique(codePrefix));
        req.setName(namePrefix + "-customer");
        req.setContact("联系人");
        req.setPhone("13800000000");
        req.setAddress("客户地址");
        return req;
    }

    public static UpdateCustomerRequest updateCustomer(String newName) {
        UpdateCustomerRequest req = new UpdateCustomerRequest();
        req.setName(newName);
        req.setContact("新联系人");
        return req;
    }
}