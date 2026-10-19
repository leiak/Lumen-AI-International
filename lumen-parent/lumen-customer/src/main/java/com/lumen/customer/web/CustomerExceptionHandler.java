package com.lumen.customer.web;

import com.lumen.common.api.R;
import com.lumen.customer.error.PortalErrorCode;
import com.lumen.customer.exception.CustomerException;
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

    @ExceptionHandler(CustomerException.class)
    public ResponseEntity<R<Void>> handleCustomer(CustomerException ex) {
        int code = ex.getCode();
        int http = HTTP_STATUS_BY_CODE.getOrDefault(code, deriveHttpStatus(code));
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
