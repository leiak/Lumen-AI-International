package com.lumen.finance.engine;

import com.googlecode.aviator.Expression;

import java.util.Objects;

/**
 * 编译后的 Aviator 表达式包装 (Iteration 4.3.1, PR2-T2.1).
 *
 * <p>保存原始 source、规范化后的 SHA-256 hash (cache key) 以及 Aviator 编译产物.
 * equals/hashCode 基于 hash 字段, 保证语义上"同一表达式"视为同一对象.
 */
public final class CompiledExpression {

    private final String source;
    private final String hash;
    private final Expression expression;

    public CompiledExpression(String source, String hash, Expression expression) {
        this.source = source;
        this.hash = hash;
        this.expression = expression;
    }

    public String getSource() {
        return source;
    }

    public String getHash() {
        return hash;
    }

    public Expression getExpression() {
        return expression;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CompiledExpression that)) return false;
        return Objects.equals(hash, that.hash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hash);
    }
}
