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

    /**
     * 理由太短 / 为空时 finance 侧抛的是 {@code ADJUSTMENT_REASON_REQUIRED} (400_012) ——
     * 它必须翻成门户专属的 {@code DISPUTE_REASON_REQUIRED} (400_003), 而不是笼统的
     * {@code PORTAL_PARAM_INVALID} (400_005): 前端要按 400_003 提示"理由至少 10 个字"。
     */
    @Test
    @DisplayName("ADJUSTMENT_REASON_REQUIRED → 400_003 / HTTP 400")
    void adjustmentReasonRequired() {
        assertMaps(FinanceErrorCode.ADJUSTMENT_REASON_REQUIRED, PortalErrorCode.DISPUTE_REASON_REQUIRED, 400);
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
     * <p>只断言 size 会漏掉"把某个码换成另一个" (size 不变, 测试照绿)。
     * 键集合相等把**缺失 / 多余 / 替换**三类都变成红。
     *
     * <p>⚠️ 这条断言抓不到"新增了一个 finance 码却忘了在门户侧登记" ——
     * 期望集合是硬编码的, 新码不在里面, 表却仍然只有这 8 个键。
     * 那是<b>人工约定</b> (见 {@code merchantReachableCodesAreMapped} 的
     * javadoc): 改 {@code MerchantBillingViewServiceImpl} 的抛码时,
     * 顺手把新码加进这两条断言。
     */
    @Test
    @DisplayName("翻译表的键集合 = 显式期望的 8 个码, 缺一个/多一个/换掉一个都红")
    void tableKeys() throws Exception {
        assertThat(table().keySet()).containsExactlyInAnyOrder(
                FinanceErrorCode.INVOICE_NOT_FOUND,
                FinanceErrorCode.RECONCILIATION_NOT_FOUND,
                FinanceErrorCode.PAYMENT_RECORD_NOT_FOUND,
                FinanceErrorCode.ADJUSTMENT_NOT_FOUND,
                FinanceErrorCode.RECON_NOT_DISPUTABLE,
                FinanceErrorCode.ADJUSTMENT_REASON_REQUIRED,
                FinanceErrorCode.BAD_REQUEST,
                FinanceErrorCode.REPORT_GENERATION_FAILED);
    }

    /**
     * <b>完备性</b>: 商家路径今天能触发的 finance 码, 必须全在翻译表里。
     *
     * <p>这 7 个码是 {@code MerchantBillingViewServiceImpl} 实际会抛的 —— 少了
     * 其中任何一个, 商家就会拿到 HTTP 500 + "导出失败"。
     *
     * <p>⚠️ 与 {@link #tableKeys()} 同一盲区: 这条只抓"少了", 抓不到
     * "商家多了一种可达的 finance 码却没登记"。那仍是人工约定 ——
     * <b>改 {@code MerchantBillingViewServiceImpl} 的抛码时, 必须把新码
     * 同时加进 {@link #tableKeys()} 与本用例</b>。漏了的话线上会退化成
     * HTTP 500, 但降级分支有 {@code log.warn} 留痕, 不会静默。
     */
    @Test
    @DisplayName("商家可达的 7 个 finance 码全在翻译表里 (少了任一个会红)")
    void merchantReachableCodesAreMapped() throws Exception {
        assertThat(table().keySet()).containsAll(List.of(
                FinanceErrorCode.RECONCILIATION_NOT_FOUND,
                FinanceErrorCode.INVOICE_NOT_FOUND,
                FinanceErrorCode.ADJUSTMENT_NOT_FOUND,
                FinanceErrorCode.RECON_NOT_DISPUTABLE,
                FinanceErrorCode.ADJUSTMENT_REASON_REQUIRED,
                FinanceErrorCode.BAD_REQUEST,
                FinanceErrorCode.REPORT_GENERATION_FAILED));
    }
}
