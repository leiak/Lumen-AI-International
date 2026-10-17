package com.lumen.finance.engine;

import com.lumen.finance.engine.BillingExpressionException.FinanceException_UnknownVar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Aviator 沙箱逃逸测试 (Iteration 4.3.1, PR2-T2.2).
 *
 * <p>验证 BillingScriptEngine 沙箱能拦截所有规范 §4.2 + R-1 描述的
 * 脚本注入攻击向 量. 10 个 fixture 中:
 * <ul>
 *   <li>8 个恶意表达式必须被拦截 (抛 BillingExpressionException)</li>
 *   <li>2 个合法表达式反例必须 PASS (不能误杀常规业务规则)</li>
 * </ul>
 *
 * <p><b>这是 SecurityReview hard gate.</b> 任一恶意 fixture 失败
 * (即沙箱被绕过, 成功反射/文件/进程访问) ⇒ PR2 拒绝合并.
 *
 * <p>覆盖向 量 (spec §4.2):
 * <ol>
 *   <li>{@code java.lang.Runtime.getRuntime().exec(...)} 反射</li>
 *   <li>{@code Class.forName(...)} 反射</li>
 *   <li>{@code new java.io.File(...)} 对象构造</li>
 *   <li>{@code System.exit(...)} 终止 JVM</li>
 *   <li>{@code fn(...)} lambda 定义反射链</li>
 *   <li>{@code require(...)} 模块导入 (Aviator 5.x 内建)</li>
 *   <li>{@code x.foo} 属性语法 (SYNTAX_SUGAR=false)</li>
 *   <li>未白名单 env 变量</li>
 *   <li>反例: 合法算术 {@code weight * 2.5 + 10}</li>
 *   <li>反例: 合法三元 {@code zone == 'REMOTE' ? 50 : 30}</li>
 * </ol>
 */
class BillingExpressionSandboxTest {

    private final BillingScriptEngine engine = new BillingScriptEngine();

    @Test
    void sandbox_reflection_runtime_exec_throws() {
        // 尝试用 Aviator 字符串 + Java 全限定名调用 Runtime.exec
        assertThrows(BillingExpressionException.class,
            () -> engine.eval("java.lang.Runtime.getRuntime().exec('ls')", Map.of()));
    }

    @Test
    void sandbox_classForName_throws() {
        assertThrows(BillingExpressionException.class,
            () -> engine.eval("Class.forName('java.lang.Runtime')", Map.of()));
    }

    @Test
    void sandbox_newFile_throws() {
        // Aviator 不支持 new 表达式; 即便 ALLOWED_CLASS_SET=emptySet() 也应先因语法失败
        assertThrows(BillingExpressionException.class,
            () -> engine.eval("new java.io.File('/etc/passwd')", Map.of()));
    }

    @Test
    void sandbox_systemExit_throws() {
        assertThrows(BillingExpressionException.class,
            () -> engine.eval("System.exit(0)", Map.of()));
    }

    @Test
    void sandbox_defineFunction_usingBuiltinForbidden() {
        // 试图用 Aviator fn() lambda + 反射拿 Runtime
        assertThrows(BillingExpressionException.class,
            () -> engine.eval("fn run = -> java.lang.Runtime.getRuntime(); run()", Map.of()));
    }

    @Test
    void sandbox_importViaRequire_throws() {
        // Aviator 5.x require('xxx') 不在白名单应失败 (引擎永不注册 require)
        assertThrows(BillingExpressionException.class,
            () -> engine.eval("require('java.io.File')", Map.of()));
    }

    @Test
    void sandbox_propertyAccess_throws() {
        // 'amount.foo' 属性语法被 ENABLE_PROPERTY_SYNTAX_SUGAR=false 禁用
        // 修复: 用 amount.foo 而非 x.foo — amount 在白名单但 .foo 触发 property 拦截
        // 原测试用 x.foo + 1 只触发 whitelist (x 未声明), 没真测 property syntax, 名字撒谎
        Map<String, Object> env = Map.of("amount", new BigDecimal("100"));
        // property syntax 被禁 -> 编译期 EXPRESSION_INVALID 错误
        assertThrows(BillingExpressionException.class,
            () -> engine.eval("amount.foo + 1", env));
    }

    @Test
    void sandbox_unknownVariableRejected() {
        // 即使 env 中传入了非白名单变量, eval 应拒绝
        assertThrows(FinanceException_UnknownVar.class,
            () -> engine.eval("secret + 1", Map.of("secret", 42)));
    }

    @Test
    void sandbox_legitimateExpression_passes() {
        // 反例: 合法算术表达式不应被误杀
        // 修复: 用 compareTo 而非 equals — BigDecimal.equals scale 敏感, 假设 Aviator scale=1
        // 是实现细节, 升级 Aviator 若改 scale 测试会突然失败
        assertDoesNotThrow(() -> {
            BigDecimal r = engine.eval("weight * 2.5 + 10",
                Map.of("weight", new BigDecimal("4")));
            assertEquals(0, r.compareTo(new BigDecimal("20")));
        });
    }

    @Test
    void sandbox_legitimateTernary_passes() {
        // 反例: 合法三元表达式不应被误杀
        assertDoesNotThrow(() -> {
            BigDecimal r = engine.eval("zone == 'REMOTE' ? 50 : 30",
                Map.of("zone", "REMOTE"));
            assertEquals(new BigDecimal("50"), r);
        });
    }
}
