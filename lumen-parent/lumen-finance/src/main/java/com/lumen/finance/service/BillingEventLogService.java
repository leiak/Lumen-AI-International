package com.lumen.finance.service;

import com.lumen.finance.dto.BillingEventLogDtos.View;

import java.util.List;

/**
 * 事件溯源服务 (Iteration 4.3.1, PR3 代码质量修复, 4.3.1-PR3 Issue 4).
 *
 * <p>对应 {@code t_billing_event_log}. 仅提供 list 查询 (admin only), 走 controller → service → mapper 分层.
 *
 * <p>所有方法接受 {@code tenantId} 显式参数 (不依赖 TenantContext), 与同模块 service 风格对齐.
 */
public interface BillingEventLogService {

    /**
     * 分页列出指定租户的事件日志 (admin only).
     *
     * @param tenantId       租户 ID
     * @param businessType   业务类型过滤 (LAST_MILE / WAREHOUSE_IN / WAREHOUSE_OUT); null/blank = 不过滤
     * @param processStatus  处理状态过滤 (RECEIVED / SUCCESS / FAILED / NO_RULE); null/blank = 不过滤
     * @param pageNum        页码 (从 1 开始; < 1 归 1)
     * @param pageSize       每页大小 (< 1 归 20, > 200 截断到 200)
     * @return View DTO 列表 (已映射 entity → DTO)
     */
    List<View> list(Long tenantId, String businessType, String processStatus,
                    Integer pageNum, Integer pageSize);
}