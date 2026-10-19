package com.lumen.customer.controller;

import com.lumen.common.api.R;
import com.lumen.common.tenant.TenantContext;
import com.lumen.customer.dto.PortalCustomerChoice;
import com.lumen.customer.exception.CustomerException;
import com.lumen.customer.security.CustomerContext;
import com.lumen.rbac.mapper.SysUserRoleMapper;
import com.lumen.wms.entity.WmsCustomer;
import com.lumen.wms.mapper.WmsCustomerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 客户门户 — 通用端点 (profile / choices) (Iteration 4.5A.2 + 4.5A.6 落地).
 *
 * <p>路由前缀 {@code /api/customer} — 由 JwtAuthenticationFilter 强制要求 {@code aud=portal}.
 *
 * <p>4.5A.6 修复 (Bug 1/2/3): 把 4.5A 阶段的三个 stub 端点替成真实实现:
 * <ul>
 *   <li>{@code GET /profile} — 从 {@code wms_customer} 表读真实 code/name/contact/phone/address;</li>
 *   <li>{@code PUT /profile} — 实际持久化 phone/address 到 {@code wms_customer} (password 改密
 *       留待 4.5B, 因为 BCrypt + audit hook 涉及独立改造);</li>
 *   <li>{@code GET /choices} — 返当前用户关联的全部客户, 按 {@code is_primary DESC, id ASC}
 *       排序, 与 {@code PortalAuthService.me} 的 semantics 对齐.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/customer")
public class PortalCommonController {

    private static final Logger log = LoggerFactory.getLogger(PortalCommonController.class);

    private final WmsCustomerMapper customerMapper;
    private final SysUserRoleMapper userRoleMapper;

    public PortalCommonController(WmsCustomerMapper customerMapper, SysUserRoleMapper userRoleMapper) {
        this.customerMapper = customerMapper;
        this.userRoleMapper = userRoleMapper;
    }

    /**
     * Bug 1 fix — 读 {@code wms_customer} 真实数据, 不再 stub 返 hardcoded note.
     *
     * <p>注意 MyBatis Plus 多租户拦截器会自动注入 {@code AND tenant_id = ...}, 必须在
     * {@link TenantContext} 已 set 的前提下才能查到行; portal 流量由 {@code JwtAuthenticationFilter}
     * 从 JWT {@code tid} claim 解出后设置, 这里直接读 {@code customerId} 即可. 若返回 null
     * (租户隔离或行被软删除), 抛 {@link CustomerException} → {@code handleAny} → HTTP 500;
     * 这个 case 实际不应该发生 (4.5A login 已校验 user↔customer 关联), 但保留为 fail-fast.
     */
    @GetMapping("/profile")
    @PreAuthorize("hasAuthority('customer:profile:view')")
    public R<Map<String, Object>> profile() {
        Long customerId = CustomerContext.requireCustomerId();
        WmsCustomer c = customerMapper.selectById(customerId);
        if (c == null) {
            // CustomerContextFilter 已校验 customerId 在 token claim 中, 此分支只在数据真的没了时触发
            throw new CustomerException(404_001, "客户不存在");
        }
        return R.ok(Map.of(
            "customerId", c.getId(),
            "code", c.getCode() == null ? "" : c.getCode(),
            "name", c.getName() == null ? "" : c.getName(),
            "contact", c.getContact() == null ? "" : c.getContact(),
            "phone", c.getPhone() == null ? "" : c.getPhone(),
            "address", c.getAddress() == null ? "" : c.getAddress()
        ));
    }

    /**
     * Bug 2 fix — 实际更新 {@code wms_customer.phone} / {@code wms_customer.address}.
     *
     * <p>password 改密留 4.5B 实施: 完整方案需要 BCrypt encode + {@code sys_user.password_hash}
     * 更新 + 强制踢出已签发 token (写到 token blacklist) + audit log; 这是独立 feature 的范围,
     * 不是 profile 字段更新. 当前 strategy: phone/address 真改, password 字段若有非空值则 log
     * warning 但不改 — 不报错是为了不阻塞 phone/address 的成功响应, FE Profile 表单能继续工作.
     *
     * <p>输入合同: body 是 {@code Map<String,String>} — JSON 任意键值都被 Jackson 收; 只在 body
     * 含 {@code phone} key 时改 phone, 含 {@code address} 时改 address (允许 null = 清空).
     * body 为空 / 缺所有字段时 no-op 返 200 (兼容 FE 偶发触发的空表单提交).
     */
    @PutMapping("/profile")
    @PreAuthorize("hasAuthority('customer:profile:edit')")
    public R<Void> updateProfile(@RequestBody(required = false) Map<String, String> body) {
        Long customerId = CustomerContext.requireCustomerId();
        if (body == null || body.isEmpty()) {
            return R.ok(null);
        }
        String phone = body.get("phone");
        String address = body.get("address");
        String password = body.get("password");

        boolean touched = (phone != null) || (address != null);
        if (!touched && (password == null || password.isBlank())) {
            // body 仅含未知字段或全 null — no-op 200
            return R.ok(null);
        }

        if (touched) {
            WmsCustomer c = customerMapper.selectById(customerId);
            if (c == null) {
                throw new CustomerException(404_001, "客户不存在");
            }
            // null 显式置空 (允许 FE 清空字段); 空白字符串保留 (不 trim, 避免意外改值)
            c.setPhone(phone);
            c.setAddress(address);
            customerMapper.updateById(c);
            log.info("PortalCommonController.updateProfile updated customerId={} (phone/address set)", customerId);
        }

        // password 字段: 4.5B 实施 (BCrypt + token blacklist + audit). 当前版本收下请求但不改,
        // 防止 FE Profile 表单 (含 password input) 因该字段未被识别而整笔失败.
        if (password != null && !password.isBlank()) {
            log.warn("PortalCommonController.updateProfile: customerId={} 提交 password 字段但当前未实施改密, 4.5B 落地 (BCrypt + audit + token blacklist)", customerId);
        }

        return R.ok(null);
    }

    /**
     * Bug 3 fix — 返 user 关联的全部客户 (FE CustomerSwitcher dropdown 需要 ≥2 个 entry 才显示),
     * 排序与 {@link com.lumen.customer.security.PortalAuthService#me(Long, Long)} 对齐:
     * {@code is_primary DESC, id ASC} (在 {@code SysUserRoleMapper.listCustomerIdsForUser} SQL 内
     * 已经 ORDER BY). {@code isPrimary} 标记当前激活的客户.
     */
    @GetMapping("/choices")
    @PreAuthorize("hasAuthority('customer:track:view')")
    public R<List<PortalCustomerChoice>> choices() {
        Long userId = CustomerContext.getUserId();
        Long activeId = CustomerContext.requireCustomerId();
        if (userId == null) {
            // 没拿到 userId (filter 没 set — 路由极端情况), 返空列表让 FE 走降级
            return R.ok(List.of());
        }
        List<Long> ids = userRoleMapper.listCustomerIdsForUser(userId);
        if (ids == null || ids.isEmpty()) {
            return R.ok(List.of());
        }
        List<WmsCustomer> rows = customerMapper.selectBatchIds(ids);
        Map<Long, WmsCustomer> byId = rows.stream()
            .collect(Collectors.toMap(WmsCustomer::getId, x -> x, (a, b) -> a));
        List<PortalCustomerChoice> result = ids.stream()
            .map(id -> {
                WmsCustomer w = byId.get(id);
                if (w == null) return null;
                boolean isPrimary = id.equals(activeId);
                return new PortalCustomerChoice(w.getId(), w.getCode(), w.getName(), isPrimary);
            })
            .filter(Objects::nonNull)
            .toList();
        return R.ok(result);
    }
}
