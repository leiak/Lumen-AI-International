package com.lumen.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumen.common.api.R;
import com.lumen.common.tenant.TenantContext;
import com.lumen.wms.dto.CreateOutboundNoteRequest;
import com.lumen.wms.dto.OutboundLineDto;
import com.lumen.wms.dto.PickOutboundLineRequest;
import com.lumen.wms.dto.ShipOutboundRequest;
import com.lumen.wms.dto.UpdateThresholdConfigRequest;
import com.lumen.wms.entity.WmsInventory;
import com.lumen.wms.service.WmsInventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成测试:出库单端到端流程 (PR4-T4.3, Iteration 3.0a).
 *
 * <p>覆盖:
 * <ol>
 *   <li>{@code outbound_fullFlow_pick_done_decrements_inv} — create → submit(阈值=∞)
 *       → PICKING(lockedQty=5) → pickLine → REVIEW → ship → SHIPPED → complete →
 *       DONE;最终库存余额从 100 减到 95,锁定全部清零.</li>
 *   <li>{@code outbound_insufficientInventory_throws409} — 库存只有 100 时申请 200,
 *       submit() 触发 {@code InsufficientInventoryException} → HTTP 409.</li>
 *   <li>{@code outbound_thresholdExceeded_then_approve_then_done} — 阈值金额=1,
 *       submit → PENDING_APPROVAL → approve → PICKING → pick → ship → complete → DONE.</li>
 * </ol>
 *
 * <p>约定:
 * <ul>
 *   <li>复用 {@link IntegrationBase} — MySQL testcontainer + Flyway 自动建表.</li>
 *   <li>用 {@code admin/admin123} 登录拿 JWT, 所有请求挂 {@code Bearer} token.</li>
 *   <li>{@code no} 用 {@code OUT-IT-{nanos}} 后缀确保跨测试/容器复用不撞唯一约束.</li>
 * </ul>
 */
@DisplayName("出库单端到端集成测试")
class OutboundFlowIT extends IntegrationBase {

    @Autowired
    ObjectMapper om;
    @Autowired
    WmsInventoryService inventoryService;

    private String token;
    private Long warehouseId;
    private Long locationId;
    private Long itemId;

    @BeforeEach
    void setUp() {
        // 1. 登录 admin (V2 rbac.sql 种子)
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        String loginBody = "{\"tenantId\":1,\"username\":\"admin\",\"password\":\"admin123\"}";
        @SuppressWarnings("unchecked")
        ResponseEntity<R> loginResp = rest.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(loginBody, h),
                R.class);
        assertThat(loginResp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> loginData = (Map<String, Object>) loginResp.getBody().getData();
        token = (String) loginData.get("accessToken");

        // 2. 复位阈值(避免其它 IT 改动残留) — 出库阈值=0, 任何 positive amount/qty 必超
        resetThreshold();

        // 3. set tenant context for service-level inventory ops (MP TenantLineInnerInterceptor)
        TenantContext.set(1L);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * 主流程:阈值=∞(设为大数)→ submit 直接 PICKING + 锁定库存 → pickLine → REVIEW
     * → ship → SHIPPED → complete → DONE;最终余额 100→95,锁库存清零.
     */
    @Test
    @DisplayName("主流程:阈值不超 → PICKING → SHIPPED → DONE,库存 -5")
    void outbound_fullFlow_pick_done_decrements_inv() throws Exception {
        warehouseId = createWarehouse();
        locationId = createLocation(warehouseId);
        itemId = createItem(new BigDecimal("10.00"));

        // 灌库存余额 = 100
        seedInventory(warehouseId, locationId, itemId, new BigDecimal("100"));

        // 阈值设大 — 不触发审批
        UpdateThresholdConfigRequest th = new UpdateThresholdConfigRequest();
        th.setOutAmountThreshold(new BigDecimal("99999999"));
        th.setOutQtyThreshold(new BigDecimal("99999999"));
        th.setApprovalRequiredWhenExceed(1);
        updateThreshold(th);

        // create + submit (直接进 PICKING + lockQty(5))
        Long noteId = createOutboundNote(warehouseId, itemId, new BigDecimal("5"));
        submit(noteId);
        assertThat(getStatus(noteId)).isEqualTo("PICKING");
        assertThat(getInventory(warehouseId, locationId, itemId).getLockedQty())
                .as("submit 必须把出库数量加到 lockQty")
                .isEqualByComparingTo(new BigDecimal("5"));

        // pickLine → releaseLock → REVIEW
        Long lineId = getFirstLineId(noteId);
        pick(noteId, lineId, new BigDecimal("5"));
        assertThat(getStatus(noteId)).isEqualTo("REVIEW");
        assertThat(getInventory(warehouseId, locationId, itemId).getLockedQty())
                .as("pickLine 必须释放掉对应锁库存")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(getInventory(warehouseId, locationId, itemId).getQty())
                .as("pickLine 必须物理扣减余额 100→95(subtractQty)")
                .isEqualByComparingTo(new BigDecimal("95"));

        // ship + complete → DONE
        // 注: pickLine 已经物理扣减 qty + 释放 locked_qty,
        // complete 的 flushLock 是 no-op (locked_qty 已为 0).
        ship(noteId, "顺丰", "SF" + System.nanoTime());
        assertThat(getStatus(noteId)).isEqualTo("SHIPPED");

        JsonNode done = complete(noteId);
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(done.get("shippedAt").isNull()).isFalse();
        assertThat(getInventory(warehouseId, locationId, itemId).getLockedQty())
                .as("complete 后锁库存必须清零")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * 库存不足以支持本次出库时,submit() 触发 {@code InsufficientInventoryException}
     * → BizException → HTTP 409.
     */
    @Test
    @DisplayName("库存不足 → submit 拒绝 → 4xx (InsufficientInventoryException → 409)")
    void outbound_insufficientInventory_throws409() throws Exception {
        warehouseId = createWarehouse();
        locationId = createLocation(warehouseId);
        itemId = createItem(new BigDecimal("10.00"));

        // 只 100 单位余额,申请 200
        seedInventory(warehouseId, locationId, itemId, new BigDecimal("100"));

        Long noteId = createOutboundNote(warehouseId, itemId, new BigDecimal("200"));

        // submit 必须失败
        HttpHeaders h = authHeaders();
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/outbound-notes/" + noteId + "/submit",
                HttpMethod.POST,
                new HttpEntity<>("", h),
                R.class);
        assertThat(resp.getStatusCode().value())
                .as("submit must fail with 409 Conflict (INSUFFICIENT_INVENTORY=50007)")
                .isEqualTo(409);
        // 锁库存不应有变化(事务回滚)
        assertThat(getInventory(warehouseId, locationId, itemId).getLockedQty())
                .as("submit 失败时 lockQty 必须回滚")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * 阈值金额=1(必超)→ submit 进 PENDING_APPROVAL → approve → PICKING →
     * pick → ship → complete → DONE.
     */
    @Test
    @DisplayName("阈值超 → submit 进 PENDING_APPROVAL → approve 翻 PICKING → ... → DONE")
    void outbound_thresholdExceeded_then_approve_then_done() throws Exception {
        warehouseId = createWarehouse();
        locationId = createLocation(warehouseId);
        itemId = createItem(new BigDecimal("10.00"));

        seedInventory(warehouseId, locationId, itemId, new BigDecimal("100"));

        // amount 阈值 = 1,出库 5 × 10 = 50 必超
        UpdateThresholdConfigRequest th = new UpdateThresholdConfigRequest();
        th.setOutAmountThreshold(new BigDecimal("1"));
        th.setOutQtyThreshold(new BigDecimal("99999999"));
        th.setApprovalRequiredWhenExceed(1);
        updateThreshold(th);

        Long noteId = createOutboundNote(warehouseId, itemId, new BigDecimal("5"));
        submit(noteId);
        assertThat(getStatus(noteId)).isEqualTo("PENDING_APPROVAL");

        approve(noteId);
        assertThat(getStatus(noteId)).isEqualTo("PICKING");

        Long lineId = getFirstLineId(noteId);
        pick(noteId, lineId, new BigDecimal("5"));
        assertThat(getStatus(noteId)).isEqualTo("REVIEW");

        ship(noteId, "圆通", "YT" + System.nanoTime());
        assertThat(getStatus(noteId)).isEqualTo("SHIPPED");

        JsonNode done = complete(noteId);
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(getInventory(warehouseId, locationId, itemId).getLockedQty())
                .as("approve→pick→ship→complete 流程后锁库存必须清零")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }

    private void resetThreshold() {
        HttpHeaders gh = authHeaders();
        ResponseEntity<R> getResp = rest.exchange(
                "/api/v1/wms/threshold",
                HttpMethod.GET,
                new HttpEntity<>(gh),
                R.class);
        assertThat(getResp.getStatusCode().value()).isEqualTo(200);

        UpdateThresholdConfigRequest th = new UpdateThresholdConfigRequest();
        th.setInbAmountThreshold(new BigDecimal("0"));
        th.setInbQtyThreshold(new BigDecimal("0"));
        th.setOutAmountThreshold(new BigDecimal("0"));
        th.setOutQtyThreshold(new BigDecimal("0"));
        th.setAdjAmountThreshold(new BigDecimal("0"));
        th.setAdjQtyThreshold(new BigDecimal("0"));
        th.setApprovalRequiredWhenExceed(1);
        updateThreshold(th);
    }

    private void updateThreshold(UpdateThresholdConfigRequest req) {
        HttpHeaders hh = authHeaders();
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/threshold",
                HttpMethod.PUT,
                new HttpEntity<>(req, hh),
                R.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    private Long createWarehouse() throws Exception {
        String body = String.format(
                "{\"code\":\"WH-OB-%d\",\"name\":\"IT Warehouse\",\"type\":\"NORMAL\",\"status\":\"ACTIVE\"}",
                System.nanoTime());
        HttpHeaders h = authHeaders();
        @SuppressWarnings("unchecked")
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/warehouses",
                HttpMethod.POST,
                new HttpEntity<>(body, h),
                R.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        return ((Number) data.get("id")).longValue();
    }

    private Long createLocation(Long whId) throws Exception {
        String body = String.format(
                "{\"warehouseId\":%d,\"code\":\"LOC-OB-%d\",\"status\":\"ACTIVE\"}",
                whId, System.nanoTime());
        HttpHeaders h = authHeaders();
        @SuppressWarnings("unchecked")
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/locations",
                HttpMethod.POST,
                new HttpEntity<>(body, h),
                R.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        return ((Number) data.get("id")).longValue();
    }

    private Long createItem(BigDecimal unitCost) throws Exception {
        // WmsItem 的字段名是 sku 而不是 code
        String body = String.format(
                "{\"sku\":\"SKU-OB-%d\",\"name\":\"IT Item\",\"uom\":\"PCS\",\"unitCost\":%s,\"status\":\"ACTIVE\"}",
                System.nanoTime(), unitCost.toPlainString());
        HttpHeaders h = authHeaders();
        @SuppressWarnings("unchecked")
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/items",
                HttpMethod.POST,
                new HttpEntity<>(body, h),
                R.class);
        assertThat(resp.getStatusCode().value())
                .as("create item body=%s", body)
                .isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        return ((Number) data.get("id")).longValue();
    }

    /**
     * IT 阶段的库存预热 — 直接调 {@link WmsInventoryService#addQty} 把余额写到种子行.
     * {@code TenantContext} 已在 {@code setUp} 里 set, 此处不需要再设/清.
     */
    private void seedInventory(Long whId, Long locId, Long iId, BigDecimal qty) {
        inventoryService.getOrInit(whId, locId, iId);
        inventoryService.addQty(whId, locId, iId, qty);
    }

    /**
     * 读库存行 — 直接查 service,避免依赖 list APIs 的查询字段.
     */
    private WmsInventory getInventory(Long whId, Long locId, Long iId) {
        return inventoryService.get(whId, locId, iId);
    }

    private Long createOutboundNote(Long whId, Long itemId, BigDecimal qty) throws Exception {
        String uniqueNo = "OUT-IT-" + System.nanoTime();
        CreateOutboundNoteRequest req = new CreateOutboundNoteRequest();
        req.setNo(uniqueNo);
        req.setCustomerId(1L);
        req.setWarehouseId(whId);
        OutboundLineDto line = new OutboundLineDto();
        line.setItemId(itemId);
        line.setQty(qty);
        line.setSourceLocationId(locationId);
        req.setLines(List.of(line));

        HttpHeaders h = authHeaders();
        @SuppressWarnings("unchecked")
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/outbound-notes",
                HttpMethod.POST,
                new HttpEntity<>(req, h),
                R.class);
        assertThat(resp.getStatusCode().value())
                .as("create outbound body=%s", om.writeValueAsString(req))
                .isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        return ((Number) data.get("id")).longValue();
    }

    private void submit(Long noteId) {
        HttpHeaders h = authHeaders();
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/outbound-notes/" + noteId + "/submit",
                HttpMethod.POST,
                new HttpEntity<>("", h),
                R.class);
        assertThat(resp.getStatusCode().value())
                .as("submit outbound id=%d body=%s", noteId, resp.getBody())
                .isEqualTo(200);
    }

    private void approve(Long noteId) {
        HttpHeaders h = authHeaders();
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/outbound-notes/" + noteId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>("", h),
                R.class);
        assertThat(resp.getStatusCode().value())
                .as("approve outbound id=%d body=%s", noteId, resp.getBody())
                .isEqualTo(200);
    }

    private void pick(Long noteId, Long lineId, BigDecimal qty) {
        PickOutboundLineRequest req = new PickOutboundLineRequest();
        req.setQty(qty);
        HttpHeaders h = authHeaders();
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/outbound-notes/" + noteId + "/lines/" + lineId + "/pick",
                HttpMethod.POST,
                new HttpEntity<>(req, h),
                R.class);
        assertThat(resp.getStatusCode().value())
                .as("pick outbound id=%d line=%d body=%s", noteId, lineId, resp.getBody())
                .isEqualTo(200);
    }

    private void ship(Long noteId, String carrier, String trackingNo) {
        ShipOutboundRequest req = new ShipOutboundRequest();
        req.setCarrier(carrier);
        req.setTrackingNo(trackingNo);
        HttpHeaders h = authHeaders();
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/outbound-notes/" + noteId + "/ship",
                HttpMethod.POST,
                new HttpEntity<>(req, h),
                R.class);
        assertThat(resp.getStatusCode().value())
                .as("ship outbound id=%d body=%s", noteId, resp.getBody())
                .isEqualTo(200);
    }

    private JsonNode complete(Long noteId) throws Exception {
        HttpHeaders h = authHeaders();
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/outbound-notes/" + noteId + "/complete",
                HttpMethod.POST,
                new HttpEntity<>("", h),
                R.class);
        assertThat(resp.getStatusCode().value())
                .as("complete outbound id=%d body=%s", noteId, resp.getBody())
                .isEqualTo(200);
        return getNote(noteId);
    }

    private JsonNode getNote(Long noteId) throws Exception {
        HttpHeaders h = authHeaders();
        ResponseEntity<String> resp = rest.exchange(
                "/api/v1/wms/outbound-notes/" + noteId,
                HttpMethod.GET,
                new HttpEntity<>(h),
                String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode root = om.readTree(resp.getBody());
        assertThat(root.get("code").asInt()).isEqualTo(0);
        return root.get("data");
    }

    private String getStatus(Long noteId) throws Exception {
        return getNote(noteId).get("status").asText();
    }

    private Long getFirstLineId(Long noteId) throws Exception {
        JsonNode data = getNote(noteId);
        return data.get("lines").get(0).get("id").asLong();
    }
}
