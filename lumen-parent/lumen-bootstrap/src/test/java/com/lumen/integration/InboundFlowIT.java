package com.lumen.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumen.common.api.R;
import com.lumen.wms.dto.AddInboundLineRequest;
import com.lumen.wms.dto.CreateInboundNoteRequest;
import com.lumen.wms.dto.InboundLineDto;
import com.lumen.wms.dto.PutawayInboundLineRequest;
import com.lumen.wms.dto.ReceiveInboundLineRequest;
import com.lumen.wms.dto.UpdateThresholdConfigRequest;
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
 * 集成测试：入库单端到端流程 (PR3-T3.5, Iteration 3.0a).
 *
 * <p>覆盖：
 * <ol>
 *   <li>{@code inbound_fullFlow_noApproval} — create → submit (阈值=0) → RECEIVING
 *       → receiveLine → PUTAWAY → putawayLine → DONE.</li>
 *   <li>{@code inbound_thresholdExceeded_routesToPendingApproval} — 把入库金额阈值
 *       设成 0.01 → submit → PENDING_APPROVAL → approve → RECEIVING.</li>
 *   <li>{@code inbound_cancel_done_throws} — 跑完流程到 DONE，cancel() 触发业务异常
 *       → HTTP 400 (BizException).</li>
 * </ol>
 *
 * <p>约定：
 * <ul>
 *   <li>复用 {@link IntegrationBase} — MySQL testcontainer + Flyway 自动建表.</li>
 *   <li>用 {@code admin/admin123} 登录拿 JWT, 所有请求挂 {@code Bearer} token.</li>
 *   <li>{@code no} 用 {@code INB}-IT-{nanos} 后缀确保跨测试/容器复用不撞唯一约束.</li>
 *   <li>{@code ThresholdEvaluator} 看的是 amount + qty 双维度，任一维度超即走审批.
 *       这里用 amount 阈值 (0.01) 触发 — qty 阈值不动.</li>
 * </ul>
 */
@DisplayName("入库单端到端集成测试")
class InboundFlowIT extends IntegrationBase {

    @Autowired
    ObjectMapper om;

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

        // 2. 复位阈值（避免其它 IT 改动残留） — 入库金额阈值=0 + qty 阈值=0，
        //    任何 positive amount/qty 都会超出 → 默认走 PENDING_APPROVAL
        resetThreshold();
    }

    /**
     * 主流程：阈值=∞（设为大数）→ submit 直接 RECEIVING → receive → PUTAWAY
     * → putaway → DONE.
     */
    @Test
    @DisplayName("主流程：阈值不超 → RECEIVING → PUTAWAY → DONE")
    void inbound_fullFlow_noApproval() throws Exception {
        // 1) 种子：仓库 / 库位 / 货品
        warehouseId = createWarehouse();
        locationId = createLocation(warehouseId);
        itemId = createItem(new BigDecimal("10.00"));

        // 2) 把入库阈值设为大数 — 不触发审批
        UpdateThresholdConfigRequest th = new UpdateThresholdConfigRequest();
        th.setInbAmountThreshold(new BigDecimal("99999999"));
        th.setInbQtyThreshold(new BigDecimal("99999999"));
        th.setApprovalRequiredWhenExceed(1);
        updateThreshold(th);

        // 3) create inbound note
        Long noteId = createInboundNote(warehouseId, itemId, new BigDecimal("5"));

        // 4) submit → 应当 RECEIVING（阈值未超）
        submit(noteId);
        assertThat(getStatus(noteId)).isEqualTo("RECEIVING");

        // 5) receive → 收齐后自动翻转为 PUTAWAY
        Long lineId = getFirstLineId(noteId);
        receive(noteId, lineId, new BigDecimal("5"));
        assertThat(getStatus(noteId)).isEqualTo("PUTAWAY");

        // 6) putaway → 上架完所有行后翻转为 DONE + 设置 completedAt
        putaway(noteId, lineId, new BigDecimal("5"), locationId);
        JsonNode done = getNote(noteId);
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(done.get("completedAt").isNull()).isFalse();
    }

    /**
     * 阈值超 → submit 路由到 PENDING_APPROVAL → approve → RECEIVING.
     */
    @Test
    @DisplayName("阈值超 → submit 进 PENDING_APPROVAL → approve 翻 RECEIVING")
    void inbound_thresholdExceeded_routesToPendingApproval() throws Exception {
        warehouseId = createWarehouse();
        locationId = createLocation(warehouseId);
        itemId = createItem(new BigDecimal("10.00"));

        // amount 阈值设为 0.01 — 任何 positive amount 必超
        UpdateThresholdConfigRequest th = new UpdateThresholdConfigRequest();
        th.setInbAmountThreshold(new BigDecimal("0.01"));
        th.setInbQtyThreshold(new BigDecimal("99999999"));
        th.setApprovalRequiredWhenExceed(1);
        updateThreshold(th);

        Long noteId = createInboundNote(warehouseId, itemId, new BigDecimal("5"));
        submit(noteId);
        assertThat(getStatus(noteId)).isEqualTo("PENDING_APPROVAL");

        // approve → RECEIVING
        approve(noteId);
        assertThat(getStatus(noteId)).isEqualTo("RECEIVING");
    }

    /**
     * DONE 单据不可取消 — cancel() 触发 IllegalNoteStateException →
     * 后端 BizException → HTTP 400.
     */
    @Test
    @DisplayName("DONE 单据调 cancel → 业务异常")
    void inbound_cancel_done_throws() throws Exception {
        warehouseId = createWarehouse();
        locationId = createLocation(warehouseId);
        itemId = createItem(new BigDecimal("10.00"));

        // 阈值设大 — 直接走主流程
        UpdateThresholdConfigRequest th = new UpdateThresholdConfigRequest();
        th.setInbAmountThreshold(new BigDecimal("99999999"));
        th.setInbQtyThreshold(new BigDecimal("99999999"));
        th.setApprovalRequiredWhenExceed(1);
        updateThreshold(th);

        Long noteId = createInboundNote(warehouseId, itemId, new BigDecimal("5"));
        submit(noteId);
        Long lineId = getFirstLineId(noteId);
        receive(noteId, lineId, new BigDecimal("5"));
        putaway(noteId, lineId, new BigDecimal("5"), locationId);
        assertThat(getStatus(noteId)).isEqualTo("DONE");

        // cancel on DONE — 应当抛业务异常 (HTTP 400)
        HttpHeaders h = authHeaders();
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/inbound-notes/" + noteId + "/cancel",
                HttpMethod.POST,
                new HttpEntity<>("", h),
                R.class);
        assertThat(resp.getStatusCode().value())
                .as("cancel on DONE must fail with 4xx (BizException)")
                .isGreaterThanOrEqualTo(400)
                .isLessThan(500);
        assertThat(resp.getBody().getCode()).isNotEqualTo(0);
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
        // ThresholdConfigController.get() 走 getOrInit() — 首次访问会插入默认行.
        // 否则 PUT update() 找不到行会抛 "阈值配置未找到" (HTTP 404).
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
                "{\"code\":\"WH-IT-%d\",\"name\":\"IT Warehouse\",\"type\":\"NORMAL\",\"status\":\"ACTIVE\"}",
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
                "{\"warehouseId\":%d,\"code\":\"LOC-IT-%d\",\"status\":\"ACTIVE\"}",
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
                "{\"sku\":\"SKU-IT-%d\",\"name\":\"IT Item\",\"uom\":\"PCS\",\"unitCost\":%s,\"status\":\"ACTIVE\"}",
                System.nanoTime(), unitCost.toPlainString());
        HttpHeaders h = authHeaders();
        @SuppressWarnings("unchecked")
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/items",
                HttpMethod.POST,
                new HttpEntity<>(body, h),
                R.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        return ((Number) data.get("id")).longValue();
    }

    private Long createInboundNote(Long whId, Long itemId, BigDecimal qty) throws Exception {
        String uniqueNo = "INB-IT-" + System.nanoTime();
        CreateInboundNoteRequest req = new CreateInboundNoteRequest();
        req.setNo(uniqueNo);
        req.setCustomerId(1L);
        req.setWarehouseId(whId);
        InboundLineDto line = new InboundLineDto();
        line.setItemId(itemId);
        line.setQty(qty);
        req.setLines(List.of(line));

        HttpHeaders h = authHeaders();
        @SuppressWarnings("unchecked")
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/inbound-notes",
                HttpMethod.POST,
                new HttpEntity<>(req, h),
                R.class);
        assertThat(resp.getStatusCode().value())
                .as("create inbound body=%s", om.writeValueAsString(req))
                .isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        return ((Number) data.get("id")).longValue();
    }

    private void submit(Long noteId) {
        HttpHeaders h = authHeaders();
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/inbound-notes/" + noteId + "/submit",
                HttpMethod.POST,
                new HttpEntity<>("", h),
                R.class);
        assertThat(resp.getStatusCode().value())
                .as("submit inbound id=%d body=%s", noteId, resp.getBody())
                .isEqualTo(200);
    }

    private void approve(Long noteId) {
        HttpHeaders h = authHeaders();
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/inbound-notes/" + noteId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>("", h),
                R.class);
        assertThat(resp.getStatusCode().value())
                .as("approve inbound id=%d body=%s", noteId, resp.getBody())
                .isEqualTo(200);
    }

    private void receive(Long noteId, Long lineId, BigDecimal qty) {
        ReceiveInboundLineRequest req = new ReceiveInboundLineRequest();
        req.setQty(qty);
        HttpHeaders h = authHeaders();
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/inbound-notes/" + noteId + "/lines/" + lineId + "/receive",
                HttpMethod.POST,
                new HttpEntity<>(req, h),
                R.class);
        assertThat(resp.getStatusCode().value())
                .as("receive inbound id=%d line=%d body=%s", noteId, lineId, resp.getBody())
                .isEqualTo(200);
    }

    private void putaway(Long noteId, Long lineId, BigDecimal qty, Long locId) {
        PutawayInboundLineRequest req = new PutawayInboundLineRequest();
        req.setQty(qty);
        req.setLocationId(locId);
        HttpHeaders h = authHeaders();
        ResponseEntity<R> resp = rest.exchange(
                "/api/v1/wms/inbound-notes/" + noteId + "/lines/" + lineId + "/putaway",
                HttpMethod.POST,
                new HttpEntity<>(req, h),
                R.class);
        assertThat(resp.getStatusCode().value())
                .as("putaway inbound id=%d line=%d body=%s", noteId, lineId, resp.getBody())
                .isEqualTo(200);
    }

    private JsonNode getNote(Long noteId) throws Exception {
        HttpHeaders h = authHeaders();
        ResponseEntity<String> resp = rest.exchange(
                "/api/v1/wms/inbound-notes/" + noteId,
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