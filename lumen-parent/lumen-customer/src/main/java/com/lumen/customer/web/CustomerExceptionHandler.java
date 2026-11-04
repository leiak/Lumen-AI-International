package com.lumen.customer.web;

import com.lumen.common.api.R;
import com.lumen.customer.error.PortalErrorCode;
import com.lumen.customer.exception.CustomerException;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 4.5A.6-Bug4: Routes {@link CustomerException} to its declared {@code httpStatus} +
 * {@code code} (e.g. 404_001 → HTTP 404 + body.code=404001).
 *
 * <p>Before this handler, {@link CustomerException} fell through to
 * {@code com.lumen.common.error.GlobalExceptionHandler.handleAny} and returned HTTP 500
 * regardless of the error code semantics (404_001, 401_003, 403_001, …).
 *
 * <p>Adaptations from the initial sketch:
 * <ul>
 *   <li>{@code R.error(code, message)} → {@code R.fail(code, message)} — actual {@code R}
 *       factory method (see {@link R#fail(int, String)}).</li>
 *   <li>{@link CustomerException} exposes only {@code getCode()}/{@code getMessage()}; the
 *       HTTP status is NOT carried on the exception itself. We look it up via
 *       {@link PortalErrorCode#getHttpStatus()} for known codes (401_003, 403_001..003,
 *       404_001) and fall back to deriving from the code prefix (401_xxx → 401,
 *       403_xxx → 403, 404_xxx → 404, 5xx_xxx → 5xx) so that future codes (e.g. 401_001,
 *       401_002 used by {@code CustomerContextFilter}) still resolve to a sensible status
 *       without enum churn.</li>
 * </ul>
 */
@RestControllerAdvice(basePackageClasses = com.lumen.customer.CustomerModuleMarker.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CustomerExceptionHandler {

    /** Lookup table: int error code → int HTTP status (built once at class-load). */
    private static final Map<Integer, Integer> HTTP_STATUS_BY_CODE =
            Arrays.stream(PortalErrorCode.values())
                    .collect(Collectors.toMap(
                            PortalErrorCode::getCode,
                            PortalErrorCode::getHttpStatus,
                            (a, b) -> a));

    /**
     * Finance 码 → 门户码的显式翻译表 (4.5C-2).
     *
     * <p><b>为什么需要这张表。</b> {@link FinanceException} 继承 {@code BizException},
     * 所以 {@code GlobalExceptionHandler.handleBiz} 本来就会照它自己的 httpStatus
     * 返回 —— 也就是说这<b>不是</b>把 500 救回 4xx 的问题, 而是 code 与 http 的语义对齐问题。
     * 但仍必须显式翻译, 理由有两条:
     *
     * <ul>
     *   <li><b>ADJUSTMENT_NOT_FOUND 的 httpStatus 是 400, 不是 404</b> (V29 定的, 改不动:
     *       已有数据与既有调用方都依赖它)。商家视角下"调账单不是我的"必须是 404 ——
     *       返回 400 等于告诉他"东西存在, 只是你不能动", 泄露存在性。</li>
     *   <li>门户前端按 {@code 404_00x} 族做统一空态渲染, finance 的 {@code 404_006} /
     *       {@code 404_007} 门户根本不认识, 会掉进兜底分支显示原始错误串。</li>
     * </ul>
     *
     * <p>表里没有的 finance 码一律降级成 {@link PortalErrorCode#EXPORT_FAILED} 语义
     * 的 HTTP 500 —— 走到这里说明 finance 抛了个门户没预料的码, 属于服务端 bug,
     * 不该伪装成业务错误码。
     *
     * <p><b>{@code BAD_REQUEST} 必须在这张表里</b>。finance 侧对 {@code period}
     * 格式非法抛的是 {@code FinanceErrorCode.BAD_REQUEST} (400_022 / HTTP 400) ——
     * 商家传 {@code ?period=2026-13} 属于入参错误, 该回 400。表里没有它的话,
     * 那把 400 会被降级规则翻成 HTTP 500, 等于把"你参数写错了"报成"服务器内部错误"。
     */
    private static final Map<FinanceErrorCode, PortalErrorCode> FINANCE_TO_PORTAL = Map.of(
            FinanceErrorCode.INVOICE_NOT_FOUND,         PortalErrorCode.INVOICE_NOT_FOUND,
            FinanceErrorCode.RECONCILIATION_NOT_FOUND,  PortalErrorCode.RECON_NOT_FOUND,
            FinanceErrorCode.PAYMENT_RECORD_NOT_FOUND,  PortalErrorCode.PAYMENT_NOT_FOUND,
            FinanceErrorCode.ADJUSTMENT_NOT_FOUND,       PortalErrorCode.ADJUSTMENT_NOT_FOUND,
            FinanceErrorCode.RECON_NOT_DISPUTABLE,      PortalErrorCode.RECON_NOT_DISPUTABLE,
            FinanceErrorCode.BAD_REQUEST,               PortalErrorCode.PORTAL_PARAM_INVALID,
            FinanceErrorCode.REPORT_GENERATION_FAILED,  PortalErrorCode.EXPORT_FAILED);

    @ExceptionHandler(CustomerException.class)
    public ResponseEntity<R<Void>> handleCustomer(CustomerException ex) {
        int code = ex.getCode();
        int http = HTTP_STATUS_BY_CODE.getOrDefault(code, deriveHttpStatus(code));
        return ResponseEntity.status(http).body(R.fail(code, ex.getMessage()));
    }

    /**
     * 4.5C-2 — 把 finance 域的 {@link FinanceException} 翻成门户码。
     *
     * <p>{@code @RestControllerAdvice(basePackageClasses = CustomerModuleMarker.class)}
     * + {@code @Order(HIGHEST_PRECEDENCE)} 让本 advice 抢在
     * {@code GlobalExceptionHandler} 前面拿到 portal 包里的异常, 所以这个分支一定会命中。
     */
    @ExceptionHandler(FinanceException.class)
    public ResponseEntity<R<Void>> handleFinance(FinanceException ex) {
        PortalErrorCode mapped = FINANCE_TO_PORTAL.get(ex.getErrorCode());
        int code = mapped != null ? mapped.getCode() : PortalErrorCode.EXPORT_FAILED.getCode();
        int http = mapped != null ? mapped.getHttpStatus() : 500;
        return ResponseEntity.status(http).body(R.fail(code, ex.getMessage()));
    }

    /**
     * Derive HTTP status from an error code whose major-digit group matches a 4xx/5xx
     * bucket (convention used across {@code PortalErrorCode}: {@code 404_001 → 404},
     * {@code 401_003 → 401}, {@code 403_002 → 403}, …).
     */
    private static int deriveHttpStatus(int code) {
        int major = code / 1000;
        return (major >= 400 && major < 600) ? major : 500;
    }
}
