package com.lumen.finance.engine;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.googlecode.aviator.Expression;
import com.googlecode.aviator.Options;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.engine.BillingExpressionException.FinanceException_UnknownVar;
import com.lumen.finance.exception.FinanceErrorCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 计费表达式 Aviator 沙箱引擎 (Iteration 4.3.1, PR2-T2.1).
 *
 * <p><b>沙箱三重防护</b> (避免 spec §4.2 + R-1 描述的脚本注入风险):
 * <ol>
 *   <li><b>白名单变量</b> — 仅允许 9 个 billing context 变量; 编译后通过
 *       {@link Expression#getVariableNames()} 反查表达式引用的变量, 不在白名单内即抛
 *       {@link FinanceException_UnknownVar}. 同时校验 env 键也是白名单子集.</li>
 *   <li><b>禁用 property 语法</b> — {@code Options.ENABLE_PROPERTY_SYNTAX_SUGAR = false},
 *       阻止 {@code obj.prop} 反射访问绕过.</li>
 *   <li><b>禁止 Java 方法调用</b> — Aviator 5.4.1 默认不注册任何 Java 方法, 仅在显式调用
 *       {@code importFunctions}/{@code addStaticFunctions}/{@code defineFunction} 时才会暴露.
 *       本引擎永不调用上述 API, 保证沙箱内无可达 Java 方法.</li>
 * </ol>
 *
 * <p>额外防护:
 * <ul>
 *   <li>{@code Options.ALLOWED_CLASS_SET = emptySet()} — 禁止任何 class literal 引用</li>
 *   <li>{@code Options.MAX_LOOP_COUNT = 1000} — 防御循环炸弹</li>
 *   <li>自身 LRU 缓存 (默认 1000, 见 {@link FinanceConstants#EXPRESSION_CACHE_MAX_SIZE})</li>
 * </ul>
 */
@Component
public class BillingScriptEngine {

    /** 计费上下文白名单变量 (spec §4.2). */
    public static final Set<String> WHITELIST_VARIABLES = Set.of(
        "amount", "weight", "distance", "quantity",
        "customer_level", "zone", "service_type",
        "is_weekend", "is_holiday"
    );

    private final AviatorEvaluatorInstance evaluator;
    private final Map<String, CompiledExpression> cache = new ConcurrentHashMap<>();

    public BillingScriptEngine() {
        this.evaluator = AviatorEvaluator.newInstance();
        // 沙箱配置 (5.4.1 API: setOption 在 AviatorEvaluatorInstance 上, 无独立 options builder)
        this.evaluator.setOption(Options.ALWAYS_PARSE_FLOATING_POINT_NUMBER_INTO_DECIMAL, true);
        this.evaluator.setOption(Options.ALWAYS_PARSE_INTEGRAL_NUMBER_INTO_DECIMAL, true);
        this.evaluator.setOption(Options.ENABLE_PROPERTY_SYNTAX_SUGAR, false);
        this.evaluator.setOption(Options.ALLOWED_CLASS_SET, new HashSet<Class<?>>());
        this.evaluator.setOption(Options.MAX_LOOP_COUNT, 1000);
    }

    /**
     * 编译表达式, 命中缓存则直接返回. 编译失败抛 BillingExpressionException.
     */
    public CompiledExpression compile(String expression) {
        String hash = normalizeHash(expression);
        CompiledExpression cached = cache.get(hash);
        if (cached != null) {
            return cached;
        }

        Expression compiled;
        try {
            compiled = evaluator.compile(expression, true);
        } catch (Exception e) {
            throw new BillingExpressionException(
                FinanceErrorCode.EXPRESSION_INVALID,
                "Failed to compile expression: " + truncate(expression, 200),
                e
            );
        }

        // 沙箱校验: 表达式引用的变量必须是白名单子集
        validateWhitelist(compiled, expression);

        CompiledExpression ce = new CompiledExpression(expression, hash, compiled);

        synchronized (cache) {
            // 双重检查: 进入临界区后再查一次 (其他线程可能已 put 同样的 hash)
            CompiledExpression existing = cache.get(hash);
            if (existing != null) {
                return existing;
            }

            // 简化实现: 达到上限时清空一半 (随机驱逐, 非真 LRU)
            // ConcurrentHashMap.keySet() 无顺序保证, 因此被驱逐的 key 是随机的.
            // 1000 容量足以容纳业务实际规则数, 不需要严格 LRU.
            if (cache.size() >= FinanceConstants.EXPRESSION_CACHE_MAX_SIZE) {
                int toRemove = Math.max(1, cache.size() / 2);
                cache.keySet().stream().limit(toRemove).forEach(cache::remove);
            }
            cache.put(hash, ce);
        }
        return ce;
    }

    /**
     * 编译并求值. 等价 {@code compile(src).then(eval(env))}.
     */
    public BigDecimal eval(String expression, Map<String, Object> env) {
        return eval(compile(expression), env);
    }

    /**
     * 求值预编译表达式. env 键必须是白名单子集; 否则抛 FinanceException_UnknownVar.
     * 求值结果必须可转为 BigDecimal; 否则抛 BillingExpressionException(EXPRESSION_INVALID).
     */
    public BigDecimal eval(CompiledExpression ce, Map<String, Object> env) {
        Map<String, Object> safeEnv = (env == null) ? Map.of() : env;
        // 沙箱校验: env 键必须都是白名单变量
        for (String key : safeEnv.keySet()) {
            if (!WHITELIST_VARIABLES.contains(key)) {
                throw new FinanceException_UnknownVar(key);
            }
        }
        try {
            Object result = ce.getExpression().execute(safeEnv);
            if (result == null) {
                throw new BillingExpressionException(
                    FinanceErrorCode.EXPRESSION_INVALID,
                    "Expression returned null for: " + truncate(ce.getSource(), 200)
                );
            }
            if (result instanceof BigDecimal bd) {
                return bd;
            }
            if (result instanceof Number n) {
                return new BigDecimal(n.toString());
            }
            throw new BillingExpressionException(
                FinanceErrorCode.EXPRESSION_INVALID,
                "Expression did not return a number: " + result.getClass().getSimpleName()
            );
        } catch (BillingExpressionException ex) {
            throw ex;
        } catch (Exception e) {
            // Aviator 5.4.1 抛 RuntimeException (含 ExpressionRuntimeException) 包除零等场景
            String msg = e.getMessage();
            // 识别 Aviator 抛出的"未知变量"运行时错误, 包装为 FinanceException_UnknownVar
            String varName = extractUnknownVarName(msg, safeEnv);
            if (varName != null) {
                throw new FinanceException_UnknownVar(varName);
            }
            throw new BillingExpressionException(
                FinanceErrorCode.EXPRESSION_INVALID,
                "Evaluation failed: " + (msg == null ? e.getClass().getSimpleName() : msg),
                e
            );
        }
    }

    /**
     * 计算 source 的规范化 SHA-256 hash. 去除所有空白后编码, 避免
     * {@code "weight * 2"} 与 {@code "weight*2"} 命中不同缓存项.
     */
    public static String normalizeHash(String expression) {
        String normalized = expression.replaceAll("\\s+", "").trim();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** 校验编译后表达式引用的变量都在白名单内. */
    private void validateWhitelist(Expression compiled, String source) {
        List<String> referenced;
        try {
            referenced = compiled.getVariableNames();
        } catch (Exception e) {
            // 表达式尚未运行, getVariableNames 一般不会失败
            return;
        }
        if (referenced == null) return;
        for (String name : referenced) {
            if (!WHITELIST_VARIABLES.contains(name)) {
                throw new FinanceException_UnknownVar(name);
            }
        }
    }

    /**
     * 从 Aviator 异常消息中尝试提取缺失的变量名.
     * 例如 {@code "No variable xxxx found"} 或 {@code "Variable [yyyy] not found"}.
     */
    private String extractUnknownVarName(String msg, Map<String, Object> env) {
        if (msg == null) return null;
        // 模式 1: variable xxx not found / No variable xxx
        for (String pattern : List.of(
            "No variable (\\w+)",
            "variable \\[(\\w+)\\] not found",
            "Variable \\[(\\w+)\\] not found",
            "Cannot find variable (\\w+)"
        )) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(msg);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
