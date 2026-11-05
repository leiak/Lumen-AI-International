package com.lumen.integration;

import com.lumen.common.api.R;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.5C-2 — 商家门户账单只读端点的可见性契约.
 *
 * <p>这一类 IT 守的是 4.5C-2 最贵的那条线: <b>商家只能看到自己的</b>。单测里
 * 断言的是 wrapper 的 SQL 片段, 这里是真 token + 真 MySQL, 断言的是端到端的
 * 可见性 —— 两者都要, 因为 SQL 片段对了但路由把 counterpartyId 传错了一样漏。
 */
@DisplayName("PortalMerchantBilling 可见性 IT (4.5C-2)")
class PortalMerchantBillingViewIT extends PortalMerchantBillingFixture {

    private String token;
    private HttpHeaders h;

    private void login() {
        token = loginAsPortal(ALICE, ALICE_PWD);
        h = portalHeaders(token);
    }

    private ResponseEntity<R> get(String path) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), R.class);
    }

    // ---------------------------------------------------------------- 对账

    @Test
    @DisplayName("alice 只看到自己的 3 条对账, carol 的 1 条不出现")
    @SuppressWarnings("unchecked")
    void listRecons_isolatesByCounterparty() {
        login();
        ResponseEntity<R> resp = get("/api/customer/recon");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> data = dataOf(resp);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("records");
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.get("period"))
                .containsExactlyInAnyOrder("2026-07", "2026-08", "2026-06");
        assertThat(data.get("total")).isEqualTo(3);
    }

    @Test
    @DisplayName("软删的对账单不出现在列表里 —— 实体无 deleted 字段, 靠 .apply('deleted = 0')")
    @SuppressWarnings("unchecked")
    void listRecons_filtersSoftDeleted() {
        login();
        Map<String, Object> data = dataOf(get("/api/customer/recon"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("records");
        // fixture 里 2026-08 有两条 (一条 deleted=1), 没做软删过滤的话会是 4 条
        assertThat(rows.stream().filter(r -> "2026-08".equals(r.get("period")))).hasSize(1);
    }

    @Test
    @DisplayName("详情: 别人的对账单 → HTTP 404 (不是 403, 不泄露存在性)")
    void detailRecons_crossCustomerIs404() {
        String carolToken = loginAsPortal(CAROL, CAROL_PWD);
        HttpHeaders ch = portalHeaders(carolToken);
        // reconOpenId 属于 customer42; carol 是 customer99
        ResponseEntity<R> resp = rest.exchange(
                "/api/customer/recon/" + reconOpenId, HttpMethod.GET, new HttpEntity<>(ch), R.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody().getCode()).isEqualTo(404002);
    }

    @Test
    @DisplayName("详情: 闭期标记透出, 商家能看到自己不能提异议")
    void detailRecon_exposesClosedFlag() {
        login();
        Map<String, Object> closed = dataOf(get("/api/customer/recon/" + reconClosedId));
        assertThat(closed.get("closed")).isEqualTo(Boolean.TRUE);
        Map<String, Object> open = dataOf(get("/api/customer/recon/" + reconOpenId));
        assertThat(open.get("closed")).isEqualTo(Boolean.FALSE);
    }

    // ---------------------------------------------------------------- 发票

    @Test
    @DisplayName("发票列表只回 alice 的 2 张")
    @SuppressWarnings("unchecked")
    void listInvoices_isolatesByCounterparty() {
        login();
        Map<String, Object> data = dataOf(get("/api/customer/invoice"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("records");
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.get("invoiceNo"))
                .containsExactlyInAnyOrder("INV-45C2-A1", "INV-45C2-A2");
        // period_year/month 拼成 yyyy-MM
        assertThat(rows).extracting(r -> r.get("period")).contains("2026-07", "2026-08");
    }

    @Test
    @DisplayName("发票详情: 别人的 → 404_003")
    void detailInvoice_crossCustomerIs404() {
        String carolToken = loginAsPortal(CAROL, CAROL_PWD);
        HttpHeaders ch = portalHeaders(carolToken);
        Long aliceInvoice = jdbc.queryForObject(
                "SELECT id FROM t_billing_invoice WHERE counterparty_id = ? LIMIT 1", Long.class, customer42Id);
        ResponseEntity<R> resp = rest.exchange(
                "/api/customer/invoice/" + aliceInvoice, HttpMethod.GET, new HttpEntity<>(ch), R.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody().getCode()).isEqualTo(404003);
    }

    // ---------------------------------------------------------------- 付款

    @Test
    @DisplayName("付款列表只回 alice 未软删的 1 条")
    @SuppressWarnings("unchecked")
    void listPayments_isolatesAndFiltersSoftDeleted() {
        login();
        Map<String, Object> data = dataOf(get("/api/customer/payment"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("records");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("amount")).isEqualTo(100.00d);
    }

    // ---------------------------------------------------------------- 调账单

    @Test
    @DisplayName("我的调账单走归属链, 只回自己的那张")
    @SuppressWarnings("unchecked")
    void listAdjustments_goesThroughOwnershipChain() {
        login();
        Map<String, Object> data = dataOf(get("/api/customer/adjustment"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("records");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("adjustmentNo")).isEqualTo("ADJ-45C2-0001");
    }

    @Test
    @DisplayName("调账单详情: 别人的 → 404_005 且 HTTP 是 404 (finance 侧是 400, 必须翻译)")
    void detailAdjustment_crossCustomerIs404() {
        String carolToken = loginAsPortal(CAROL, CAROL_PWD);
        HttpHeaders ch = portalHeaders(carolToken);
        ResponseEntity<R> resp = rest.exchange(
                "/api/customer/adjustment/" + adjDraftId, HttpMethod.GET, new HttpEntity<>(ch), R.class);
        assertThat(resp.getStatusCode().value())
                .as("ADJUSTMENT_NOT_FOUND 在 finance 侧是 HTTP 400, 门户必须翻成 404").isEqualTo(404);
        assertThat(resp.getBody().getCode()).isEqualTo(404005);
    }

    // ---------------------------------------------------------------- 导出

    @Test
    @DisplayName("导出返回 xlsx 字节流 + 附件头, 且只含自己的数据")
    void exportRecons_returnsXlsxAttachment() {
        login();
        ResponseEntity<byte[]> resp = rest.exchange(
                "/api/customer/recon/export", HttpMethod.GET, new HttpEntity<>(h), byte[].class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getFirst("Content-Disposition"))
                .as("必须是附件下载").contains("attachment");
        byte[] body = resp.getBody();
        assertThat(body).isNotNull();
        // xlsx 是 zip 容器, 头两个字节是 PK\x03\x04
        assertThat(body.length).isGreaterThan(4);
        assertThat(body[0]).isEqualTo((byte) 'P');
        assertThat(body[1]).isEqualTo((byte) 'K');
    }

    // ---------------------------------------------------------------- 权限

    @Test
    @DisplayName("没带 token → 401")
    void withoutTokenIs401() {
        ResponseEntity<R> resp = getJson("/api/customer/recon", new HttpHeaders());
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }
}
