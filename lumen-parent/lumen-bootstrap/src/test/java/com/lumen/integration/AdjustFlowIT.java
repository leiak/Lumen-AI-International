package com.lumen.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumen.common.api.R;
import com.lumen.common.tenant.TenantContext;
import com.lumen.wms.dto.CreateInventoryAdjustRequest;
import com.lumen.wms.dto.InventoryAdjustLineDto;
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
 * 集成测试：库存调整单端到端流程 (PR5-T5.2, Iteration 3.0a).
 *
 * <p>覆盖：
 * <ol>
 *   <li>{@code adjust_fullFlow_execute_appliesDeltas} — 阈值不超,正负 delta 都能从
 *       create(DRAFT) → submit(APPROVED) → execute(DONE) 一把跑通，
 *       验证库存余额确实被 adjustDelta 加/减了.</li>
 *   <li>{@code adjust_thresholdExceeded_then_approve_then_execute} — amount 阈值=1
 *       必超 → submit(PENDING_APPROVAL) → approve(APPROVED) → execute(DONE)，
 *       同样验证 delta 应用.</li>
 *   <li>{@code adjust_execute_fromDraft_throws409} — DRAFT 直接 execute →
 *       {@code IllegalNoteStateException} → HTTP 409 (与 422 / 400 的契约对齐).</li>
 * </ol>
 *
 * <p>约定：
 * <ul>
 *   <li>复用 {@link IntegrationBase} — MySQL testcontainer + Flyway 自动建表.</li>
 *   <li>用 {@code admin/admin123} 登录拿 JWT, 所有请求挂 {@code Bearer} token.</li>
 *   <li>{@code no} 用 {@code ADJ}-IT-{nanos} 后缀确保跨测试/容器复用不撞唯一约束.</li>
 * </ul>
 */
@DisplayName("库存调整单端到端集成测试")
class AdjustFlowIT extends IntegrationBase {

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

        // 2. 复位阈值（避免其它 IT 改动残留）
        resetThreshold();

        // 3. set tenant context for service-level inventory ops
        TenantContext.set(1L);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * 主流程：阈值不超 → submit 直进 APPROVED → execute 把每行 deltaQty 加/减到库存.
     */
    @Test
    @DisplayName("主流程：阈值不超 → DRAFT → APPROVED → DONE,正负 delta 落地")
    void adjust_fullFlow_execute_appliesDeltas() throws Exception {
        warehouseId = createWarehouse();
        locationId = createLocation(warehouseId);
        itemId = createItem(new BigDecimal("10.00"));

        // 灌库存余额 = 100
        seedInventory(warehouseId, locationId, itemId, new BigDecimal("100"));

        // adj 阈值设大 — 不触发审批
        UpdateThresholdConfigRequest th = new UpdateThresholdConfigRequest();
        th.setAdjAmountThreshold(new BigDecimal("99999999"));
        th.setAdjQtyThreshold(new BigDecimal("99999999"));
        th.setApprovalRequiredWhenExceed(1);
        updateThreshold(th);

        Long noteId = createAdjustNote(warehouseId, itemId, new BigDecimal("+5"));
        submit(noteId);
        assertThat(getStatus(noteId)).isEqualTo("APPROVED");

        // execute 前：余额 = 100
        assertThat(getInventory(warehouseId, locationId, itemId).getQty())
                .as("execute 前库存余额")
                .isEqualByComparingTo(new BigDecimal("100"));

        execute(noteId);
        assertThat(getStatus(noteId)).isEqualTo("DONE");
        assertThat(getInventory(warehouseId, locationId, itemId).getQty())
                .as("execute 后正 delta 必须 +5")
                .isEqualByComparingTo(new BigDecimal("105"));

        // 负 delta 单 (新单 + 新行)— 验证减库存
        Long noteId2 = createAdjustNote(warehouseId, itemId, new BigDecimal("-3"));
        submit(noteId2);
        assertThat(getStatus(noteId2)).isEqualTo("APPROVED");
        execute(noteId2);
        assertThat(getStatus(noteId2)).isEqualTo("DONE");
        assertThat(getInventory(warehouseId, locationId, itemId).getQty())
                .as("execute 后负 delta 必须 -3")
                .isEqualByComparingTo(new BigDecimal("102"));
    }

    /**
     * 阈值超 → submit 进 PENDING_APPROVAL → approve → APPROVED → execute → DONE.
     */
    @Test
    @DisplayName("阈值超 → submit 进 PENDING_APPROVAL → approve 翻 APPROVED → execute → DONE")
    void adjust_thresholdExceeded_then_approve_then_execute() throws Exception {
        warehouseId = createWarehouse();
        locationId = createLocation(warehouseId);
        itemId = createItem(new BigDecimal("10.00"));

        seedInventory(warehouseId, locationId, itemId, new BigDecimal("100"));

        // amount 阈值=1,unitCost=10 × +5 → amount=50 必超
        UpdateThresholdConfigRequest th = new UpdateThresholdConfigRequest();
        th.setAdjAmountThreshold(new BigDecimal("1"));
        th.setAdjQtyThreshold(new BigDecimal("99999999"));
        th.setApprovalRequiredWhenExceed(1);
        updateThreshold(th);

        Long noteId = createAdjustNote(warehouseId, itemId, new BigDecimal("+5"));
        submit(noteId);
        assertThat(getStatus(noteId)).isEqualTo("PENDING_APPROVAL");

        approve(noteId);
        assertThat(getStatus(noteId)).isEqualTo("APPROVED");

        execute(noteId);
        assertThat(getStatus(noteId)).isEqualTo("DONE");
        assertThat(getInventory(warehouseId, locationId, itemId).getQty())
                .as("approve+execute 后库存 +5")
                .isEqualByComparingTo(new BigDecimal("105"));
    }

    /**
     * DRAFT 直接 execute → IllegalNoteStateException → HTTP 409.
     */
    @Test
    @DisplayName("execute from DRAFT → IllegalNoteStateException → 409")
    void adjust_execute_fromDraft_throws409() throws Exception {
        warehouseId = createWarehouse();
        locationId = createLocation(warehouseId);
        itemId = createItem(new BigDecimal("10.00"));
        seedInventory(warehouseId, locationId, itemId, new BigDecimal("100"));

        // 阈值设大避免 submit 自动跳状态
        UpdateThresholdConfigRequest th = new UpdateThresholdConfigRequest();
        th.setAdjAmountThreshold(new BigDecimal("99999999"));
        th.setAdjQtyThreshold(new BigDecimal("99999999"));
        th.setApprovalRequiredWhenExceed(1);
        updateThreshold(th);

        Long noteId = createAdjustNote(warehouseId, itemId, new BigDecimal("+5"));
        // 故意不 submit — 仍是 DRAFT
        assertThat(getStatus(noteId)).isEqualTo("DRAFT");

        HttpHeaders h = authHeaders();
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/inventory-adjusts/" + noteId + "/execute",
                HttpMethod.POST,
                new HttpEntity<>("", h),
                R.class);
        assertThat(resp.getStatusCode().value())
                .as("execute from DRAFT must fail with 409 (IllegalNoteStateException → 409)")
                .isEqualTo(409);
        // 库存不应有任何变化
        assertThat(getInventory(warehouseId, locationId, itemId).getQty())
                .as("execute 失败时库存不能动")
                .isEqualByComparingTo(new BigDecimal("100"));
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
                "{\"code\":\"WH-ADJ-%d\",\"name\":\"IT Warehouse\",\"type\":\"NORMAL\",\"status\":\"ACTIVE\"}",
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
                "{\"warehouseId\":%d,\"code\":\"LOC-ADJ-%d\",\"status\":\"ACTIVE\"}",
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
        String body = String.format(
                "{\"sku\":\"SKU-ADJ-%d\",\"name\":\"IT Item\",\"uom\":\"PCS\",\"unitCost\":%s,\"status\":\"ACTIVE\"}",
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

    private void seedInventory(Long whId, Long locId, Long iId, BigDecimal qty) {
        inventoryService.getOrInit(whId, locId, iId);
        inventoryService.addQty(whId, locId, iId, qty);
    }

    private WmsInventory getInventory(Long whId, Long locId, Long iId) {
        return inventoryService.get(whId, locId, iId);
    }

    private Long createAdjustNote(Long whId, Long itemId, BigDecimal deltaQty) throws Exception {
        String uniqueNo = "ADJ-IT-" + System.nanoTime();
        CreateInventoryAdjustRequest req = new CreateInventoryAdjustRequest();
        req.setNo(uniqueNo);
        req.setWarehouseId(whId);
        req.setReason("IT 自动测试");
        InventoryAdjustLineDto line = new InventoryAdjustLineDto();
        line.setItemId(itemId);
        line.setLocationId(locationId);
        line.setDeltaQty(deltaQty);
        req.setLines(List.of(line));

        HttpHeaders h = authHeaders();
        @SuppressWarnings("unchecked")
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/inventory-adjusts",
                HttpMethod.POST,
                new HttpEntity<>(req, h),
                R.class);
        assertThat(resp.getStatusCode().value())
                .as("create adjust body=%s", om.writeValueAsString(req))
                .isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        return ((Number) data.get("id")).longValue();
    }

    private void submit(Long noteId) {
        HttpHeaders h = authHeaders();
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/inventory-adjusts/" + noteId + "/submit",
                HttpMethod.POST,
                new HttpEntity<>("", h),
                R.class);
        assertThat(resp.getStatusCode().value())
                .as("submit adjust id=%d body=%s", noteId, resp.getBody())
                .isEqualTo(200);
    }

    private void approve(Long noteId) {
        HttpHeaders h = authHeaders();
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/inventory-adjusts/" + noteId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>("", h),
                R.class);
        assertThat(resp.getStatusCode().value())
                .as("approve adjust id=%d body=%s", noteId, resp.getBody())
                .isEqualTo(200);
    }

    private void execute(Long noteId) {
        HttpHeaders h = authHeaders();
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/inventory-adjusts/" + noteId + "/execute",
                HttpMethod.POST,
                new HttpEntity<>("", h),
                R.class);
        assertThat(resp.getStatusCode().value())
                .as("execute adjust id=%d body=%s", noteId, resp.getBody())
                .isEqualTo(200);
    }

    private JsonNode getNote(Long noteId) throws Exception {
        HttpHeaders h = authHeaders();
        ResponseEntity<String> resp = rest.exchange(
                "/api/v1/wms/inventory-adjusts/" + noteId,
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
}
