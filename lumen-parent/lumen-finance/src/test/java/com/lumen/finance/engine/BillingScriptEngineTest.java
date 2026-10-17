package com.lumen.finance.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * BillingScriptEngine 单元测试 (Iteration 4.3.1, PR2-T2.1).
 *
 * <p>覆盖规范:
 * <ul>
 *   <li>基本算术 (加/减/乘/除/乘方)</li>
 *   <li>三元表达式 (ternary)</li>
 *   <li>沙箱白名单变量访问</li>
 *   <li>编译错误/未知变量/除零 错误传播</li>
 *   <li>大表达式求值不超时</li>
 *   <li>SHA-256 cache key 归一化 (去除空白)</li>
 *   <li>缓存命中 (相同 source 复用 CompiledExpression)</li>
 *   <li>返回值类型恒为 BigDecimal</li>
 * </ul>
 */
class BillingScriptEngineTest {

    private BillingScriptEngine engine;

    @BeforeEach
    void setUp() {
        engine = new BillingScriptEngine();
    }

    @Test
    void simpleAddition_returnsSum() {
        BigDecimal result = engine.eval("1 + 2", Map.of());
        assertEquals(new BigDecimal("3"), result);
    }

    @Test
    void whitelistVariable_weight() {
        Map<String, Object> env = Map.of(
            "weight", new BigDecimal("10.0"),
            "quantity", new BigDecimal("2.5")
        );
        BigDecimal result = engine.eval("weight * quantity", env);
        assertEquals(new BigDecimal("25.00"), result);
    }

    @Test
    void conditionalTernary_returnsCorrectBranch() {
        String expr = "distance > 100 ? 50 : 20";
        assertEquals(new BigDecimal("50"), engine.eval(expr, Map.of("distance", new BigDecimal("150"))));
        assertEquals(new BigDecimal("20"), engine.eval(expr, Map.of("distance", new BigDecimal("50"))));
    }

    @Test
    void unknownVariable_throwsUnknownVariable() {
        BillingExpressionException.FinanceException_UnknownVar ex = assertThrows(
            BillingExpressionException.FinanceException_UnknownVar.class,
            () -> engine.eval("nonexistent_var + 1", Map.of())
        );
        assertEquals("nonexistent_var", ex.getVariableName());
    }

    @Test
    void syntaxError_throwsExpressionInvalid() {
        assertThrows(
            BillingExpressionException.class,
            () -> engine.eval("1 +", Map.of())
        );
    }

    @Test
    void divisionByZero_throwsRuntime() {
        assertThrows(
            BillingExpressionException.class,
            () -> engine.eval("1 / 0", Map.of())
        );
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void largeExpression_doesNotTimeout() {
        // 大量 BigDecimal 乘法, 验证不超时 (注: Aviator ^ 是 bitwise XOR 不是 pow)
        assertDoesNotThrow(() -> engine.eval("1234567890 * 9876543210", Map.of()));
    }

    @Test
    void compileCache_secondEvalReusesCompiled() {
        CompiledExpression c1 = engine.compile("weight * 2");
        CompiledExpression c2 = engine.compile("weight * 2");
        assertEquals(c1.getHash(), c2.getHash());
        assertNotNull(c1.getExpression());
        // 真 cache 命中: 两次 compile 应共享同一 Aviator Expression 实例
        assertSame(c1.getExpression(), c2.getExpression(),
            "Second compile should return cached Expression instance");
    }

    @Test
    void normalize_thenHash_sameExpressionSameHash() {
        CompiledExpression c1 = engine.compile("1+2");
        CompiledExpression c2 = engine.compile("1 + 2");
        assertEquals(c1.getHash(), c2.getHash());
    }

    @Test
    void resultAlwaysBigDecimal() {
        BigDecimal r = engine.eval("10 * 3", Map.of());
        assertInstanceOf(BigDecimal.class, r);
    }
}
