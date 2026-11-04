package com.lumen.customer.web;

import com.lumen.common.api.R;
import com.lumen.common.error.ErrorCode;
import com.lumen.customer.error.PortalErrorCode;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
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
        assertThat(resp.getBody().getMessage())
                .as("未映射时不能把 finance 的原始 message 回给商家")
                .isEqualTo(PortalErrorCode.EXPORT_FAILED.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static Map<ErrorCode, PortalErrorCode> table() throws Exception {
        var f = CustomerExceptionHandler.class.getDeclaredField("FINANCE_TO_PORTAL");
        f.setAccessible(true);
        return (Map<ErrorCode, PortalErrorCode>) f.get(null);
    }

    /**
     * 翻译表的键集合必须与显式期望集合**逐个相等**。
     *
     * <p>只断言 size 会漏掉两类替换: 把某个码换成另一个 (size 不变, 测试照绿),
     * 以及新增 finance 码却忘了加映射 (表 size 不变, 测试照绿)。
     */
    @Test
    @DisplayName("翻译表的键集合 = 显式期望的 7 个码, 不多不少")
    void tableKeys() throws Exception {
        assertThat(table().keySet()).containsExactlyInAnyOrder(
                FinanceErrorCode.INVOICE_NOT_FOUND,
                FinanceErrorCode.RECONCILIATION_NOT_FOUND,
                FinanceErrorCode.PAYMENT_RECORD_NOT_FOUND,
                FinanceErrorCode.ADJUSTMENT_NOT_FOUND,
                FinanceErrorCode.RECON_NOT_DISPUTABLE,
                FinanceErrorCode.BAD_REQUEST,
                FinanceErrorCode.REPORT_GENERATION_FAILED);
    }

    /**
     * <b>完备性</b>: 商家路径今天能触发的 finance 码, 必须全在翻译表里。
     *
     * <p>这 6 个码是 {@code MerchantBillingViewServiceImpl} 实际会抛的 —— 新增
     * finance 码却忘了在门户侧登记, 商家就会拿到 HTTP 500 + "导出失败"。
     * 维护成本: 改 {@code MerchantBillingViewServiceImpl} 的抛码时, 顺手核对这里。
     */
    @Test
    @DisplayName("商家可达的 6 个 finance 码全部在翻译表里 (新增码忘了登记会红)")
    void merchantReachableCodesAreMapped() throws Exception {
        assertThat(table().keySet()).containsAll(List.of(
                FinanceErrorCode.RECONCILIATION_NOT_FOUND,
                FinanceErrorCode.INVOICE_NOT_FOUND,
                FinanceErrorCode.ADJUSTMENT_NOT_FOUND,
                FinanceErrorCode.RECON_NOT_DISPUTABLE,
                FinanceErrorCode.BAD_REQUEST,
                FinanceErrorCode.REPORT_GENERATION_FAILED));
    }
}
