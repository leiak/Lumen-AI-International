package com.lumen.integration;

import com.lumen.common.api.R;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.6 — 委托单编号口径 IT.
 *
 * <p>编号由 {@code sys_number_rule} 的 code='ORDER' 产出 (V4 seed:
 * prefix='ORD', date_format='yyyyMMdd', seq_length=6, reset_policy='DAY')。
 * 口径 = {@code ORD-yyyyMMdd-6位流水}, 例如 {@code ORD-20261110-000001}。
 *
 * <p>本 IT 锁 3 件事: 格式、唯一、库里落的就是这个号 (不是前端拼的)。
 */
@DisplayName("OrderNumbering IT")
class OrderNumberingIT extends OrderAdminFixtureBase {

    @Test
    @DisplayName("order_no 形如 ORD-yyyyMMdd-6位流水, 且两单不同")
    void orderNo_followsFormatAndIsUnique() {
        String token = loginAdmin();
        HttpHeaders h = adminHeaders(token);

        Long first = orderIdOf(adminPost("/api/v1/orders", createOrderBody(), h));
        Long second = orderIdOf(adminPost("/api/v1/orders", createOrderBody(), h));

        String no1 = jdbc.queryForObject("SELECT order_no FROM t_order WHERE id = ?", String.class, first);
        String no2 = jdbc.queryForObject("SELECT order_no FROM t_order WHERE id = ?", String.class, second);

        assertThat(no1).matches("ORD-\\d{8}-\\d{6}");
        assertThat(no2).matches("ORD-\\d{8}-\\d{6}");
        assertThat(no1).isNotEqualTo(no2);
    }

    @Test
    @DisplayName("建单响应里的 orderNo 与库里一致")
    void createResponse_orderNoMatchesDatabase() {
        String token = loginAdmin();
        HttpHeaders h = adminHeaders(token);

        ResponseEntity<R> resp = adminPost("/api/v1/orders", createOrderBody(), h);
        Long id = orderIdOf(resp);
        Map<String, Object> data = dataOf(resp);

        String inDb = jdbc.queryForObject("SELECT order_no FROM t_order WHERE id = ?", String.class, id);
        assertThat(data.get("orderNo")).isEqualTo(inDb);
    }
}
