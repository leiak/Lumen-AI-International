package com.lumen.customer.web;

import com.lumen.common.tenant.TenantContext;
import com.lumen.customer.exception.CustomerException;
import com.lumen.customer.security.CustomerContext;

/**
 * 门户身份提取 (Iteration 4.5C-2 抽自 {@code PortalOrderController} 的私有方法).
 *
 * <p>存在的理由不是"少打几行字", 是<b>越权面只能有一处</b>。4.5B.2 把这两个
 * getter 写在 {@code PortalOrderController} 的 private 方法里, 加 4.5C-2 的
 * 4 个 controller 时, "从 JWT 取、绝不从 DTO 取" 这条规则就有 5 个可能被抄错的
 * 副本。抄错一次 = 商家能看到别家的对账单。
 *
 * <p>审查后 {@code PortalOrderController} 的那两份私有副本已收敛到本类
 * (逐字相同 → 行为等价)。<b>本类是 4.5C-2 的 4 个 controller 与
 * {@code PortalOrderController} 的唯一身份入口</b> —— 但不是全仓唯一:
 * 4.5A/4.5B 的 {@code PortalTrackController} / {@code PortalCommonController}
 * 仍直接读 {@code CustomerContext} / {@code TenantContext}, 尚未收敛。
 */
public final class PortalIdentity {

    private PortalIdentity() {
    }

    /**
     * 取 tenantId — 强制, 不可为 null。
     *
     * <p>{@link TenantContext} 由 {@code JwtAuthenticationFilter} 从 JWT 的
     * {@code tid} claim 填好, 所以这里读得到; 末尾那次 {@code TenantContext.set(t)}
     * 是幂等的, 保留是为了 MyBatis-Plus 多租户拦截器在不经过 filter 的调用路径
     * (如异步 / 定时) 上也能拿到值 —— 与 {@code PortalOrderController} 的既有写法一致。
     */
    public static Long requireTenantId() {
        Long t = TenantContext.get();
        if (t == null) {
            throw new CustomerException(401_001, "未识别租户 (JWT 缺失 tenantId)");
        }
        TenantContext.set(t);
        return t;
    }

    /**
     * 取当前商家 id — 强制, 不可为 null。
     *
     * <p>这是商家可见性的<b>唯一</b>来源。任何 controller 收到 body/query 里的
     * counterpartyId 都是 bug, 不要加"允许管理员指定"的分支 —— 门户 token 里
     * 没有管理员这一说。
     */
    public static Long requireCounterpartyId() {
        return CustomerContext.requireCustomerId();
    }

    /** 取当前登录用户 id — 强制, 不可为 null。 */
    public static Long requireUserId() {
        return CustomerContext.requireUserId();
    }
}
