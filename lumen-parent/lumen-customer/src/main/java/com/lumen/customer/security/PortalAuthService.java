package com.lumen.customer.security;

import com.lumen.common.exception.AuthException;
import com.lumen.common.security.JwtUtil;
import com.lumen.customer.dto.PortalCustomerChoice;
import com.lumen.customer.dto.PortalLoginRequest;
import com.lumen.customer.dto.PortalLoginResponse;
import com.lumen.customer.dto.PortalMeResponse;
import com.lumen.customer.error.PortalErrorCode;
import com.lumen.rbac.entity.SysUser;
import com.lumen.rbac.mapper.SysUserMapper;
import com.lumen.rbac.mapper.SysUserRoleMapper;
import com.lumen.wms.entity.WmsCustomer;
import com.lumen.wms.mapper.WmsCustomerMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 4.5A.2-T2.4 — 客户门户 (B2B) 登录 / 客户选择 / me 三入口核心服务.
 *
 * <p>三个对外方法:
 * <ul>
 *   <li>{@link #login(PortalLoginRequest)} — 用户名密码登录; 单客户直接发 portal token,
 *       多客户返 choices (不带 token) 让前端走 MFA-like "选客户" 步.</li>
 *   <li>{@link #selectCustomer(Long, Long)} — 多客户登录流的第二步: 校验 user↔customer
 *       关联后发 token.</li>
 *   <li>{@link #me(Long, Long)} — 给前端一份"我是谁 / 当前激活哪个客户 / 我还能切哪几个".</li>
 * </ul>
 *
 * <p>field visibility note: {@code passwordEncoder} 和 {@code jwtUtil}
 * 是 package-private + {@code @Autowired}, 不是 {@code private final},
 * 因为 {@code PortalAuthServiceTest} 走纯 Mockito 单测 (无 Spring context),
 * 必须在 {@code @BeforeEach} 里手动塞真实编码器/JWT. mapper 是 {@code @Mock} 注入,
 * 保持 package-private 让 mock setter 也能直接访问.
 */
@Service
public class PortalAuthService {

    /** tenant_id 硬编码 = 1 (MVP 单租户). 后续 4.5B 接 SAML/IdP 时换成 tenant 解析. */
    private static final long DEFAULT_TENANT_ID = 1L;

    /**
     * 客户门户 (B2B) 用户登录后统一拥有的 7 个 customer:* 权限.
     * 在 {@link #issueToken} 中塞进 JWT perms claim, 避免 controller 还要解 JWT 才能鉴权.
     */
    private static final List<String> CUSTOMER_PERMS = List.of(
        "customer:track:view",
        "customer:order:create",
        "customer:order:view",
        "customer:invoice:view",
        "customer:recon:view",
        "customer:profile:view",
        "customer:profile:edit"
    );

    @Autowired SysUserMapper userMapper;
    @Autowired SysUserRoleMapper userRoleMapper;
    @Autowired WmsCustomerMapper customerMapper;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtUtil jwtUtil;

    public PortalLoginResponse login(PortalLoginRequest req) {
        SysUser user = userMapper.selectByUsernameAndTenant(DEFAULT_TENANT_ID, req.username());
        if (user == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new AuthException(PortalErrorCode.PORTAL_AUTH_INVALID.getCode(),
                                    PortalErrorCode.PORTAL_AUTH_INVALID.getMessage());
        }
        if (!userRoleMapper.hasCustomerRole(user.getId())) {
            throw new AuthException(PortalErrorCode.PORTAL_NO_CUSTOMER_ROLE.getCode(),
                                    PortalErrorCode.PORTAL_NO_CUSTOMER_ROLE.getMessage());
        }
        List<Long> customerIds = userRoleMapper.listCustomerIdsForUser(user.getId());
        if (customerIds.isEmpty()) {
            throw new AuthException(PortalErrorCode.PORTAL_NO_CUSTOMER_LINK.getCode(),
                                    PortalErrorCode.PORTAL_NO_CUSTOMER_LINK.getMessage());
        }
        if (customerIds.size() == 1) {
            return issueToken(user, customerIds.get(0));
        }
        // 多客户: 返 choices 不返 token
        List<WmsCustomer> customers = customerMapper.selectBatchIds(customerIds);
        List<PortalCustomerChoice> choices = customers.stream()
            .map(c -> new PortalCustomerChoice(c.getId(), c.getCode(), c.getName(), false))
            .toList();
        return new PortalLoginResponse(null, null, choices, user.getId(), user.getUsername(), user.getRealName());
    }

    public PortalLoginResponse selectCustomer(Long userId, Long customerId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new AuthException(PortalErrorCode.PORTAL_USER_NOT_FOUND.getCode(),
                                    PortalErrorCode.PORTAL_USER_NOT_FOUND.getMessage());
        }
        if (!userRoleMapper.userLinkedToCustomer(userId, customerId)) {
            throw new AuthException(PortalErrorCode.PORTAL_USER_NOT_LINKED.getCode(),
                                    PortalErrorCode.PORTAL_USER_NOT_LINKED.getMessage());
        }
        return issueToken(user, customerId);
    }

    public PortalMeResponse me(Long userId, Long activeCustomerId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new AuthException(PortalErrorCode.PORTAL_USER_NOT_FOUND.getCode(),
                                    PortalErrorCode.PORTAL_USER_NOT_FOUND.getMessage());
        }
        if (!userRoleMapper.userLinkedToCustomer(userId, activeCustomerId)) {
            throw new AuthException(PortalErrorCode.PORTAL_USER_NOT_LINKED.getCode(),
                                    PortalErrorCode.PORTAL_USER_NOT_LINKED.getMessage());
        }
        WmsCustomer c = customerMapper.selectById(activeCustomerId);
        List<Long> ids = userRoleMapper.listCustomerIdsForUser(userId);
        List<WmsCustomer> all = customerMapper.selectBatchIds(ids);
        List<PortalCustomerChoice> choices = all.stream()
            .map(x -> new PortalCustomerChoice(x.getId(), x.getCode(), x.getName(), x.getId().equals(activeCustomerId)))
            .toList();
        return new PortalMeResponse(user.getId(), user.getUsername(), user.getRealName(),
            activeCustomerId, c == null ? null : c.getName(), choices);
    }

    /**
     * issue 一个 aud=portal / 含 active_customer_id claim 的 JWT, 把 7 个 customer:*
     * 权限统一塞 perms (避免 controller 还要解 JWT 才能鉴权).
     */
    private PortalLoginResponse issueToken(SysUser user, Long customerId) {
        Map<String, Object> extra = Map.of("active_customer_id", customerId);
        String token = jwtUtil.issueToken(user.getId(), DEFAULT_TENANT_ID,
            List.of("customer"),
            CUSTOMER_PERMS,
            extra, "portal");
        return new PortalLoginResponse(token, customerId, null, user.getId(), user.getUsername(), user.getRealName());
    }
}
