package com.lumen.customer.web;

import com.lumen.common.api.R;
import com.lumen.customer.error.PortalErrorCode;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CustomerExceptionHandler — finance 码翻译")
class CustomerExceptionHandlerFinanceTest {

    private final CustomerExceptionHandler handler = new CustomerExceptionHandler();

    private static FinanceException fe(FinanceErrorCode code) {
        return new FinanceException(code, "boom");
    }

    private void assertMaps(FinanceErrorCode from, PortalErrorCode to, int http) {
        ResponseEntity<R<Void>> resp = handler.handleFinance(fe(from));
        assertThat(resp.getStatusCode().value()).as("finance %s → HTTP %d", from, http).isEqualTo(http);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(to.getCode());
    }

    @Test
    @DisplayName("INVOICE_NOT_FOUND → 404_003 / HTTP 404")
    void invoice() {
        assertMaps(FinanceErrorCode.INVOICE_NOT_FOUND, PortalErrorCode.INVOICE_NOT_FOUND, 404);
    }

    @Test
    @DisplayName("RECONCILIATION_NOT_FOUND → 404_002 / HTTP 404")
    void recon() {
        assertMaps(FinanceErrorCode.RECONCILIATION_NOT_FOUND, PortalErrorCode.RECON_NOT_FOUND, 404);
    }

    @Test
    @DisplayName("PAYMENT_RECORD_NOT_FOUND → 404_004 / HTTP 404")
    void payment() {
        assertMaps(FinanceErrorCode.PAYMENT_RECORD_NOT_FOUND, PortalErrorCode.PAYMENT_NOT_FOUND, 404);
    }

    @Test
    @DisplayName("ADJUSTMENT_NOT_FOUND finance 侧是 HTTP 400, 必须翻成 404 —— 否则泄露存在性")
    void adjustment() {
        assertThat(FinanceErrorCode.ADJUSTMENT_NOT_FOUND.getHttpStatus())
                .as("前置: finance 侧确实是 400 (V29 定的, 不可改)").isEqualTo(400);
        assertMaps(FinanceErrorCode.ADJUSTMENT_NOT_FOUND, PortalErrorCode.ADJUSTMENT_NOT_FOUND, 404);
    }

    @Test
    @DisplayName("RECON_NOT_DISPUTABLE → 409_002 / HTTP 409")
    void notDisputable() {
        assertMaps(FinanceErrorCode.RECON_NOT_DISPUTABLE, PortalErrorCode.RECON_NOT_DISPUTABLE, 409);
    }

    @Test
    @DisplayName("REPORT_GENERATION_FAILED → 500_001 / HTTP 500")
    void exportFailed() {
        assertMaps(FinanceErrorCode.REPORT_GENERATION_FAILED, PortalErrorCode.EXPORT_FAILED, 500);
    }

    @Test
    @DisplayName("BAD_REQUEST → 400_005 / HTTP 400 —— 少了这条, period 格式错误会变成 500")
    void badRequest() {
        assertMaps(FinanceErrorCode.BAD_REQUEST, PortalErrorCode.PORTAL_PARAM_INVALID, 400);
    }

    @Test
    @DisplayName("表里没有的 finance 码 → 降级 500, 不伪装成业务码")
    void unmappedFinanceCodeDegradesTo500() {
        ResponseEntity<R<Void>> resp = handler.handleFinance(fe(FinanceErrorCode.RULE_NOT_FOUND));
        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        assertThat(resp.getBody().getCode()).isEqualTo(PortalErrorCode.EXPORT_FAILED.getCode());
    }

    @Test
    @DisplayName("翻译表里的 7 个码一一对应, 不多不少 —— 多一个就是给未映射码开了口子")
    void tableSize() throws Exception {
        var f = CustomerExceptionHandler.class.getDeclaredField("FINANCE_TO_PORTAL");
        f.setAccessible(true);
        assertThat(((Map<?, ?>) f.get(null)).size()).isEqualTo(7);
    }
}
