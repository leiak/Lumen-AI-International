package com.lumen.finance.service;

import com.lumen.finance.dto.BillingRuleDtos.CreateRequest;
import com.lumen.finance.dto.BillingRuleDtos.UpdateRequest;
import com.lumen.finance.dto.BillingRuleDtos.View;

import java.util.List;

/**
 * 计费规则 CRUD + 启用 + 停用 + 审计 (Iteration 4.3.1, PR3-T3.1).
 *
 * <p>对应 {@code t_billing_rule} + {@code t_billing_rule_item} + {@code t_billing_rule_audit}.
 * 所有方法都接受 {@code tenantId} 显式参数（不依赖 TenantContext），便于 batch / 跨租户后台任务调用.
 *
 * <p>约束：
 * <ul>
 *   <li>复合 UNIQUE KEY 由 DB 兜底（UK 重复 → {@link com.lumen.finance.exception.FinanceErrorCode#DUPLICATE_RULE}）</li>
 *   <li>{@link #activate} 编译所有 item 表达式；任一失败将 rule 置为 {@code INVALID} 后抛 {@code EXPRESSION_INVALID}</li>
 *   <li>启用 / 停用 / 创建 / 更新 都写入 {@code t_billing_rule_audit} 一行</li>
 * </ul>
 */
public interface BillingRuleService {

    /**
     * 创建规则 (status=DRAFT). 同步插入所有 items，写审计 (CREATE).
     *
     * @throws com.lumen.finance.exception.FinanceException DUPLICATE_RULE / RULE_NOT_FOUND
     */
    View create(Long tenantId, Long operatorId, CreateRequest req);

    /**
     * 查询单条规则（含 items）.
     *
     * @throws com.lumen.finance.exception.FinanceException RULE_NOT_FOUND
     */
    View get(Long tenantId, Long id);

    /**
     * 更新规则 (priority / effectiveTo / remark + 全替换 items). 写审计 (UPDATE).
     *
     * @throws com.lumen.finance.exception.FinanceException RULE_NOT_FOUND
     */
    View update(Long tenantId, Long id, Long operatorId, UpdateRequest req);

    /**
     * 启用规则：编译所有 items；任一失败将 rule 置 INVALID 并抛 EXPRESSION_INVALID.
     * 若当前已是 ACTIVE，直接返回当前 View（幂等）.
     *
     * @throws com.lumen.finance.exception.FinanceException EXPRESSION_INVALID
     */
    View activate(Long tenantId, Long id, Long operatorId);

    /**
     * 停用规则：status=DISABLED；写审计 (DISABLE).
     *
     * @throws com.lumen.finance.exception.FinanceException RULE_NOT_FOUND
     */
    View disable(Long tenantId, Long id, Long operatorId);

    /**
     * 分页列出指定租户的规则（带可选过滤项），按 priority asc, id desc 排序.
     * 返回全量 items（不分页，给前端的列表就是完整的）.
     */
    List<View> list(Long tenantId, String businessType, String direction, String status,
                    Integer pageNum, Integer pageSize);
}
