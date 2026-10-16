package com.lumen.cs.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.tenant.TenantContext;
import com.lumen.cs.entity.WmsDeliveryExceptionView;
import com.lumen.cs.mapper.WmsDeliveryExceptionViewMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CS 客服视角异常工单列表 REST 控制器 (Iteration 4.2 PR6 finalize).
 *
 * <p>前缀 {@code /api/v1/cs/exceptions}; 用于 CS 客服工作台
 * (/cs/exceptions) 的 SLA 视角列表.
 *
 * <p>实现说明:
 * <ul>
 *   <li>复用 lumen-cs 既有的 {@link WmsDeliveryExceptionView} 只读视图
 *       (PR4 引入, 映射 lumen-dispatch 的 {@code wms_delivery_exception} 表)</li>
 *   <li>支持 {@code severity} / {@code status} 过滤 + 分页</li>
 *   <li>权限守门 {@code cs:exception:view} (V25 seed); 兼容
 *       {@code cs:exception:list} (FE 菜单挂的 perm code 也是 view,
 *       但 spec 命名 list 是更直白的查询入口, 这里双接受以兼容旧契约)</li>
 * </ul>
 *
 * <p><b>注意:</b> 与 lumen-dispatch 的
 * {@link com.lumen.dispatch.controller.DeliveryExceptionController}
 * 不同 — 这是一个简化的 "只读 + 不带 SLA 计数" 版本, 真正 SLA 视角
 * (升级链 + 倒计时) 在 lumen-cs FE 端基于本接口数据再聚合 (PR6 前端计算
 * escalationStatus; PR6+ 后端将扩展出 {@code ExceptionSlaView} 完整 DTO).
 */
@RestController
@RequestMapping("/api/v1/cs/exceptions")
@RequiredArgsConstructor
public class CsExceptionController {

    private final WmsDeliveryExceptionViewMapper mapper;

    /**
     * GET /api/v1/cs/exceptions — 分页查询异常工单 (CS 视角).
     *
     * @param pageNum 当前页 (1-based, default 1)
     * @param pageSize 每页条数 (default 20, 上限 200)
     * @param severity 可选, 按 type 过滤 (DAMAGE / OVERTIME / REJECTED / ADDR_ERR).
     *                命名沿用 {@code severity} 是为对齐 lumen-cs 内部 type→severity
     *                派生 (DAMAGE=HIGH 等); 这里实际是原始 type 字段过滤.
     * @param status 可选, 按工单状态过滤 (OPEN / INVESTIGATING / RESOLVED / CLOSED / CANCELLED)
     * @return paged WmsDeliveryExceptionView list
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('cs:exception:view', 'cs:exception:list')")
    public R<PageResult<WmsDeliveryExceptionView>> list(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status) {

        long effectiveSize = Math.min(pageSize, 200L);
        Long tenantId = TenantContext.require();

        Page<WmsDeliveryExceptionView> page = new Page<>(pageNum, effectiveSize);
        LambdaQueryWrapper<WmsDeliveryExceptionView> q = new LambdaQueryWrapper<WmsDeliveryExceptionView>()
            .eq(WmsDeliveryExceptionView::getTenantId, tenantId)
            .orderByDesc(WmsDeliveryExceptionView::getCreatedAt);
        if (severity != null && !severity.isBlank()) {
            q.eq(WmsDeliveryExceptionView::getType, severity);
        }
        if (status != null && !status.isBlank()) {
            q.eq(WmsDeliveryExceptionView::getStatus, status);
        }

        Page<WmsDeliveryExceptionView> result = mapper.selectPage(page, q);
        return R.ok(PageResult.of(result.getRecords(), result.getTotal(),
            pageNum, effectiveSize));
    }
}
