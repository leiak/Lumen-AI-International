package com.lumen.rbac.service.impl;

import com.lumen.common.error.BizException;
import com.lumen.common.security.JwtUtil;
import com.lumen.common.tenant.TenantContext;
import com.lumen.rbac.dto.LoginRequest;
import com.lumen.rbac.dto.TokenResponse;
import com.lumen.rbac.entity.SysRefreshToken;
import com.lumen.rbac.entity.SysUser;
import com.lumen.rbac.entity.SysUserRole;
import com.lumen.rbac.entity.SysRole;
import com.lumen.rbac.entity.SysRolePermission;
import com.lumen.rbac.entity.SysPermission;
import com.lumen.rbac.error.RbacErrorCode;
import com.lumen.rbac.mapper.*;
import com.lumen.rbac.service.AuthService;
import com.lumen.rbac.service.LoginAuditService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
// TODO(security): Phase 8+ 引入登录失败计数器（Redis `login:fail:{username}`）+ 账号锁定。当前 MVP 仅依赖审计日志。
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRolePermissionMapper rolePermMapper;
    private final SysRefreshTokenMapper refreshMapper;
    private final LoginAuditService auditService;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;
    private final BCryptPasswordEncoder encoder;

    private static String blKey(String jti) { return "bl:" + jti; }
    private static String rtKey(String jti) { return "rt:" + jti; }

    @Override
    @Transactional
    public TokenResponse login(LoginRequest req, String ip, String ua) {
        // 登录时尚未建立租户上下文，MP 多租户拦截器会注入 tenant_id = 0 导致查不到用户。
        // 这里预先把请求体里的 tenantId 推到上下文，便于按租户过滤 + 后续审计写入正确的 tenant_id。
        if (req.getTenantId() != null) {
            TenantContext.set(req.getTenantId());
        }
        try {
            return doLogin(req, ip, ua);
        } finally {
            TenantContext.clear();
        }
    }

    private TokenResponse doLogin(LoginRequest req, String ip, String ua) {
        SysUser user = userMapper.findByUsername(req.getUsername());
        try {
            if (user == null) throw BizException.of(RbacErrorCode.USER_NOT_FOUND);
            if (!encoder.matches(req.getPassword(), user.getPasswordHash()))
                throw BizException.of(RbacErrorCode.USER_PASSWORD_WRONG);
            if (user.getStatus() == null || user.getStatus() == 0)
                throw BizException.of(RbacErrorCode.USER_DISABLED);
            Long tenantId = req.getTenantId() != null ? req.getTenantId() : user.getTenantId();

            List<String> roles = loadRoles(user.getId(), tenantId);
            List<String> perms = loadPerms(user.getId(), tenantId);

            String access = jwtUtil.issueAccess(user.getId(), tenantId, roles, perms);
            String refresh = jwtUtil.issueRefresh(user.getId(), tenantId);
            Claims c = jwtUtil.parse(refresh);
            String jti = c.getId();
            redis.opsForValue().set(rtKey(jti), String.valueOf(user.getId()), Duration.ofSeconds(jwtUtil.getRefreshTtl()));

            SysRefreshToken rec = new SysRefreshToken();
            rec.setJti(jti);
            rec.setUserId(user.getId());
            rec.setTenantId(tenantId);
            rec.setExpiresAt(LocalDateTime.now().plusSeconds(jwtUtil.getRefreshTtl()));
            refreshMapper.insert(rec);

            user.setLastLoginIp(ip);
            user.setLastLoginAt(LocalDateTime.now());
            userMapper.updateById(user);

            auditService.recordSuccess(user.getId(), user.getUsername(), tenantId, ip, ua);
            return new TokenResponse(access, refresh, jwtUtil.getAccessTtl());
        } catch (BizException ex) {
            auditService.recordFail(req.getUsername(), req.getTenantId(), ip, ua, ex.getMessage());
            throw ex;
        }
    }

    @Override
    @Transactional
    public TokenResponse refresh(String refreshToken) {
        Claims c;
        try { c = jwtUtil.parse(refreshToken); }
        catch (io.jsonwebtoken.ExpiredJwtException e) { throw BizException.of(RbacErrorCode.TOKEN_EXPIRED); }
        catch (Exception e) { throw BizException.of(RbacErrorCode.TOKEN_REVOKED); }

        String jti = c.getId();
        String uid = redis.opsForValue().get(rtKey(jti));
        if (uid == null) throw BizException.of(RbacErrorCode.TOKEN_REVOKED);

        Long userId = Long.parseLong(c.getSubject());
        Long tenantId = c.get("tid", Long.class);
        // refresh 路径从 JWT 自身的 tid claim 拿租户，推到 TenantContext；
        // 没有这一步，sys_user / sys_user_role / sys_role_permission 上的
        // MyBatis-Plus 多租户拦截器默认用 tenant_id=0 过滤，命中不到种子
        // admin (tenant_id=1)，userMapper.selectById 返回 null → 抛 USER_NOT_FOUND (404)。
        // —— 这是 AuthFlowIT.refreshTokenRotation_oldRevokedAfterRotation 暴露的 bug。
        if (tenantId != null) {
            TenantContext.set(tenantId);
        }
        try {
            SysUser user = userMapper.selectById(userId);
            if (user == null) throw BizException.of(RbacErrorCode.USER_NOT_FOUND);

            List<String> roles = loadRoles(userId, tenantId);
            List<String> perms = loadPerms(userId, tenantId);

            // rotation
            redis.delete(rtKey(jti));
            String access = jwtUtil.issueAccess(userId, tenantId, roles, perms);
            String newRefresh = jwtUtil.issueRefresh(userId, tenantId);
            Claims nc = jwtUtil.parse(newRefresh);
            redis.opsForValue().set(rtKey(nc.getId()), String.valueOf(userId), Duration.ofSeconds(jwtUtil.getRefreshTtl()));
            SysRefreshToken rec = new SysRefreshToken();
            rec.setJti(nc.getId()); rec.setUserId(userId); rec.setTenantId(tenantId);
            rec.setExpiresAt(LocalDateTime.now().plusSeconds(jwtUtil.getRefreshTtl()));
            refreshMapper.insert(rec);
            return new TokenResponse(access, newRefresh, jwtUtil.getAccessTtl());
        } finally {
            // refresh 路径没有外部 controller 设上下文；跑完务必清掉，
            // 防止 tomcat worker 线程被复用时 ThreadLocal 泄漏到下一个请求。
            TenantContext.clear();
        }
    }

    @Override
    public void logout(String accessToken) {
        try {
            Claims c = jwtUtil.parse(accessToken);
            long remainMs = c.getExpiration().getTime() - System.currentTimeMillis();
            if (remainMs > 0) redis.opsForValue().set(blKey(c.getId()), "1", Duration.ofMillis(remainMs));
            redis.delete(rtKey(c.getId()));
        } catch (Exception e) { log.warn("logout redis op failed; access token may remain valid until expiry", e); }
    }

    private List<String> loadRoles(Long userId, Long tenantId) {
        return userRoleMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysUserRole>().eq("user_id", userId).eq("tenant_id", tenantId))
                .stream().map(ur -> roleMapper.selectById(ur.getRoleId())).filter(Objects::nonNull)
                .map(SysRole::getCode).collect(Collectors.toList());
    }

    private List<String> loadPerms(Long userId, Long tenantId) {
        List<Long> roleIds = userRoleMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysUserRole>().eq("user_id", userId).eq("tenant_id", tenantId))
                .stream().map(SysUserRole::getRoleId).collect(Collectors.toList());
        if (roleIds.isEmpty()) return List.of();
        List<Long> permIds = rolePermMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysRolePermission>().in("role_id", roleIds).eq("tenant_id", tenantId))
                .stream().map(SysRolePermission::getPermissionId).collect(Collectors.toList());
        if (permIds.isEmpty()) return List.of();
        return permMapper.selectBatchIds(permIds).stream().map(SysPermission::getCode).collect(Collectors.toList());
    }
}
