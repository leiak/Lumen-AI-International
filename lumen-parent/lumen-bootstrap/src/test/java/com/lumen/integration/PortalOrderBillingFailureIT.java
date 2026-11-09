package com.lumen.integration;

import com.lumen.common.api.R;
import com.lumen.finance.FinanceConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.5D-1 —— 客户下单遇到「计费规则求值失败」时的端到端行为.
 *
 * <p>被测的用户可见症状(4.5D-1 之前):
 * <ol>
 *   <li>下单接口返回 200, DO 建成 —— 这条本来就对</li>
 *   <li><b>但账上零行痕迹</b> —— 没有 OPEN_FAILED 取证行, 将来无从补单</li>
 * </ol>
 *
 * <p>与 {@code BillingFailureAuditIT} 的差别: 那条直接调 service, 这条从 HTTP 入口打进去,
 * 走 {@code PortalOrderController → DeliveryOrderServiceImpl.createForCustomer} 的完整链路
 * (dispatch 模块, 自带 {@code @Transactional}).
 */
@DisplayName("门户下单遇到计费失败的 IT (4.5D-1)")
class PortalOrderBillingFailureIT extends PortalOrderFixtureBase {

    @Test
    @DisplayName("规则求值失败 → 下单仍成功, 账上留下 1 行 OPEN_FAILED 取证行")
    void createOrder_withFailingBillingRule_stillCreatesOrderAndLeavesFailureAudit() {
        // 坏表达式 + 不 seed override → 失败发生在 estimate 阶段
        // (客户下单走 createForCustomer → estimateAndAccrue → estimate)。
        seedActiveLastMileRule("unknown_var * 2");

        LocalDateTime start = validWindowStart();
        LocalDateTime end = start.plusHours(4);

        String token = loginAsPortal(ALICE, ALICE_PWD);
        HttpHeaders h = portalHeaders(token);

        ResponseEntity<R> resp = postJson("/api/customer/order", createOrderBody(start, end), h);

        // 1) 下单本身不受计费失败影响 —— 失败不阻塞主流程
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(0);

        Map<String, Object> data = dataOf(resp);
        long doId = ((Number) data.get("doId")).longValue();
        assertThat(doId).isPositive();
        assertThat(data.get("status")).isEqualTo("PENDING_DISPATCH");
        // DO 确实落库了 —— OPEN_FAILED 那行是 REQUIRES_NEW 独立事务写的,
        // 外层回滚它也活着, 所以光看 accrual 断言不够.
        assertThat(statusOf(doId)).isEqualTo("PENDING_DISPATCH");

        // 2) 但账上必须有失败取证痕迹 —— 4.5D-1 之前这里是零行
        List<Map<String, Object>> accruals = jdbc.queryForList(
            "SELECT status, gross_amount, tax_amount, net_amount, rule_id, error_msg "
                + "FROM t_billing_accrual WHERE business_id = ? AND deleted = 0", doId);
        assertThat(accruals)
            .as("计费失败也必须留下 1 行 OPEN_FAILED 取证行 —— 否则将来无从补单")
            .hasSize(1);
        Map<String, Object> row = accruals.get(0);
        assertThat(row.get("status")).isEqualTo(FinanceConstants.ACCRUAL_STATUS_OPEN_FAILED);
        assertThat((BigDecimal) row.get("gross_amount")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((BigDecimal) row.get("tax_amount")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((BigDecimal) row.get("net_amount")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row.get("rule_id"))
            .as("estimate 阶段失败时规则归属未知 → rule_id 为 NULL")
            .isNull();
        assertThat((String) row.get("error_msg")).isNotBlank();
    }
}
