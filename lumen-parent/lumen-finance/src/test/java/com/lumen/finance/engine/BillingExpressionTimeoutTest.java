package com.lumen.finance.engine;

import com.lumen.finance.FinanceConstants;
import com.lumen.finance.exception.FinanceErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 表达式求值超时 (spec §4.2 100ms).
 *
 * <p>Aviator 5.x 本身没有内置求值超时, 需要在外层用 Future + get(timeout, unit) 控制.
 * BillingScriptEngine.eval() 暴露一个 overload 带 timeoutMs 参数.
 */
class BillingExpressionTimeoutTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void slowExpression_timesOut() throws Exception {
        BillingScriptEngine engine = new BillingScriptEngine();
        // 25-level nested multiplication — AST has 2^25-1 ≈ 33.5M multiply nodes;
        // well past the 100ms timeout threshold.
        // (Plan used 30 levels, but L(n) = 6*2^n - 5 chars at n=30 = 6.4B, overflows
        // Java String max length; 25 levels produces ~201M chars and still reliably
        // times out.)
        String expr = "1";
        for (int i = 0; i < 25; i++) expr = "(" + expr + " * " + expr + ")";
        final String slowExpr = expr;
        // 期望: eval 在 100ms 内抛 EXPRESSION_TIMEOUT
        long start = System.currentTimeMillis();
        assertThrows(BillingExpressionException.class,
            () -> engine.eval(slowExpr, Map.of(), 100L));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 3000, "Should timeout within reasonable wall time, took " + elapsed + "ms");
    }
}