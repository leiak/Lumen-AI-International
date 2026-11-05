package com.lumen.integration;

import com.lumen.common.api.R;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.5C-2 — {@code POST /api/customer/recon/dispute} 对账异议.
 *
 * <p>核心不变量: <b>提异议不新建调账单, 只把已存在的 DRAFT 推进到 PENDING_L1</b>,
 * 并把商家主张 + 理由 + 时间写进 recon 三列 (V39)。
 */
@DisplayName("PortalRecon dispute IT (4.5C-2)")
class PortalReconDisputeIT extends PortalMerchantBillingFixture {

    private HttpHeaders headers() {
        return portalHeaders(loginAsPortal(ALICE, ALICE_PWD));
    }

    private ResponseEntity<R> dispute(String body, HttpHeaders h) {
        return postJson("/api/customer/recon/dispute", body, h);
    }

    @Test
    @DisplayName("异议成功: recon 三列落库, 调账单 DRAFT → PENDING_L1")
    @SuppressWarnings("unchecked")
    void dispute_writesColumnsAndPushesAdjustment() {
        ResponseEntity<R> resp = dispute(
                "{\"reconId\":" + reconOpenId + ",\"claimedAmount\":-12.00,\"reason\":\"9月3日那票没送到\"}",
                headers());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> data = dataOf(resp);
        assertThat(((Number) data.get("adjustmentId")).longValue()).isEqualTo(adjDraftId);
        assertThat(data.get("adjustmentStatus")).isEqualTo("PENDING_L1");
        assertThat(data.get("disputeAt")).isNotNull();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT dispute_amount, dispute_reason, dispute_at, variance_amount FROM t_reconciliation WHERE id = ?",
                reconOpenId);
        // ⚠️ DECIMAL 列经 jdbc 取回来是 BigDecimal, 不是 Double ——
        // isEqualTo(-12.00d) 走的是 equals(), BigDecimal.equals(Double) 恒 false,
        // 断言会一直红。必须比数值不比类型。
        assertThat(((BigDecimal) row.get("DISPUTE_AMOUNT")).compareTo(new BigDecimal("-12.00"))).isZero();
        assertThat(row.get("DISPUTE_REASON")).isEqualTo("9月3日那票没送到");
        assertThat(row.get("DISPUTE_AT")).isNotNull();
        // 财务算的账务金额绝不能被商家主张覆盖
        assertThat(((BigDecimal) row.get("VARIANCE_AMOUNT")).compareTo(new BigDecimal("-10.00"))).isZero();

        String status = jdbc.queryForObject(
                "SELECT status FROM t_billing_adjustment WHERE id = ?", String.class, adjDraftId);
        assertThat(status).as("关联调账单应被推进到 L1 审批").isEqualTo("PENDING_L1");

        long adjCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_billing_adjustment WHERE tenant_id = 1 AND adjustment_no LIKE ?",
                Long.class, ADJ_NO_PREFIX + "%");
        assertThat(adjCount).as("异议不该新建调账单").isEqualTo(1);
    }

    @Test
    @DisplayName("重复提 → 409_002, 调账单状态不动")
    void dispute_twiceIsConflict() {
        HttpHeaders h = headers();
        assertThat(dispute(
                "{\"reconId\":" + reconOpenId + ",\"reason\":\"第一次\"}", h).getStatusCode().value())
                .isEqualTo(200);

        ResponseEntity<R> again = dispute(
                "{\"reconId\":" + reconOpenId + ",\"reason\":\"再提一次\"}", h);
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody().getCode()).isEqualTo(409002);

        String reason = jdbc.queryForObject(
                "SELECT dispute_reason FROM t_reconciliation WHERE id = ?", String.class, reconOpenId);
        assertThat(reason).as("第二次不该覆盖第一次的理由").isEqualTo("第一次");
    }

    @Test
    @DisplayName("闭期对账单 → 409_002")
    void dispute_closedPeriodRejected() {
        ResponseEntity<R> resp = dispute(
                "{\"reconId\":" + reconClosedId + ",\"reason\":\"闭期了也要说\"}", headers());
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody().getCode()).isEqualTo(409002);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT dispute_at FROM t_reconciliation WHERE id = ?", reconClosedId);
        assertThat(row.get("DISPUTE_AT")).as("被拒的异议不该留下任何标记").isNull();
    }

    @Test
    @DisplayName("无关联调账单 → 409_002, 不写三列 (与闭期那条分开, 否则两条分支测的是同一个码)")
    void dispute_withoutLinkedAdjustmentRejected() {
        ResponseEntity<R> resp = dispute(
                "{\"reconId\":" + reconNoAdjId + ",\"reason\":\"没单可推\"}", headers());
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody().getCode()).isEqualTo(409002);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT dispute_at FROM t_reconciliation WHERE id = ?", reconNoAdjId);
        assertThat(row.get("DISPUTE_AT")).isNull();
        String status = jdbc.queryForObject(
                "SELECT status FROM t_billing_adjustment WHERE id = ?", String.class, adjDraftId);
        assertThat(status).as("别人的调账单不该被动").isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("别人的对账单 → 404_002 (不是 409)")
    void dispute_crossCustomerIs404() {
        String carolToken = loginAsPortal(CAROL, CAROL_PWD);
        ResponseEntity<R> resp = dispute(
                "{\"reconId\":" + reconOpenId + ",\"reason\":\"不是我的\"}", portalHeaders(carolToken));
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(resp.getBody().getCode()).isEqualTo(404002);
    }

    @Test
    @DisplayName("理由为空 → 400_003, 不进 service")
    void dispute_blankReasonRejected() {
        ResponseEntity<R> resp = dispute(
                "{\"reconId\":" + reconOpenId + ",\"reason\":\"   \"}", headers());
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getCode()).isEqualTo(400003);
        String status = jdbc.queryForObject(
                "SELECT status FROM t_billing_adjustment WHERE id = ?", String.class, adjDraftId);
        assertThat(status).as("入参没过就不该动调账单").isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("没给 reconId → 400_005 (入参错误), 不是 404 —— 我们压根没去查哪张对账单")
    void dispute_missingReconIdIsBadRequest() {
        ResponseEntity<R> resp = dispute("{\"reason\":\"没说对哪张单\"}", headers());
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getCode()).isEqualTo(400005);
        String status = jdbc.queryForObject(
                "SELECT status FROM t_billing_adjustment WHERE id = ?", String.class, adjDraftId);
        assertThat(status).as("入参没过就不该动调账单").isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("主张金额超 DECIMAL(18,4) → 400_004")
    void dispute_oversizedClaimRejected() {
        ResponseEntity<R> resp = dispute(
                "{\"reconId\":" + reconOpenId + ",\"claimedAmount\":123456789012345.6789,\"reason\":\"太大\"}",
                headers());
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getCode()).isEqualTo(400004);
    }

    @Test
    @DisplayName("只质疑不主张金额 → dispute_amount 存 NULL, variance 不动")
    void dispute_nullClaimStoredAsNull() {
        ResponseEntity<R> resp = dispute(
                "{\"reconId\":" + reconOpenId + ",\"reason\":\"货没送到\"}", headers());
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT dispute_amount, variance_amount FROM t_reconciliation WHERE id = ?", reconOpenId);
        assertThat(row.get("DISPUTE_AMOUNT")).isNull();
        assertThat(((BigDecimal) row.get("VARIANCE_AMOUNT")).compareTo(new BigDecimal("-10.00"))).isZero();
    }

    @Test
    @DisplayName("V39 三列存在 — Flyway 跑完后 schema 必须到位")
    void v39_columnsExist() {
        List<String> cols = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_reconciliation' "
                    + "AND COLUMN_NAME IN ('dispute_amount', 'dispute_reason', 'dispute_at')",
                String.class);
        assertThat(cols).containsExactlyInAnyOrder("dispute_amount", "dispute_reason", "dispute_at");
    }

    @Test
    @DisplayName("V40 的 3 个权限码真的落库且绑到 customer 角色 (id 14)")
    void v40_permissionsSeededAndBound() {
        List<String> codes = jdbc.queryForList(
                "SELECT p.code FROM sys_permission p JOIN sys_role_permission rp ON rp.permission_id = p.id "
                    + "WHERE rp.role_id = 14 AND p.code IN "
                    + "('customer:payment:view', 'customer:adjustment:view', 'customer:adjustment:create')",
                String.class);
        assertThat(codes).hasSize(3)
                .contains("customer:payment:view", "customer:adjustment:view", "customer:adjustment:create");
    }
}
