package com.lumen.finance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.BillingRuleDtos.CreateRequest;
import com.lumen.finance.dto.BillingRuleDtos.CreateRuleItem;
import com.lumen.finance.dto.BillingRuleDtos.RuleItemView;
import com.lumen.finance.dto.BillingRuleDtos.UpdateRequest;
import com.lumen.finance.dto.BillingRuleDtos.View;
import com.lumen.finance.engine.BillingExpressionException;
import com.lumen.finance.engine.BillingScriptEngine;
import com.lumen.finance.entity.BillingRule;
import com.lumen.finance.entity.BillingRuleAudit;
import com.lumen.finance.entity.BillingRuleItem;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingRuleAuditMapper;
import com.lumen.finance.mapper.BillingRuleItemMapper;
import com.lumen.finance.mapper.BillingRuleMapper;
import com.lumen.finance.service.BillingRuleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计费规则服务实现 (Iteration 4.3.1, PR3-T3.1).
 *
 * <p>核心规则：
 * <ol>
 *   <li>所有方法都接受 {@code tenantId} 显式参数（service 不依赖 ThreadLocal TenantContext），
 *       这样 IT 测试 / batch job / 跨租户管理场景都能简单调用</li>
 *   <li>DB 复合 UNIQUE KEY (tenant_id, business_type, direction, counterparty_type,
 *       effective_from, deleted) 由 service 翻译为 {@link FinanceErrorCode#DUPLICATE_RULE}</li>
 *   <li>{@link #activate} 调用 {@link BillingScriptEngine#compile(String)} 预编译每个 item；
 *       任一失败 → rule.status=INVALID + 审计 + 抛 {@code EXPRESSION_INVALID}</li>
 *   <li>每次写操作都追加一条 {@link BillingRuleAudit}</li>
 * </ol>
 */
@Slf4j
@Service
public class BillingRuleServiceImpl implements BillingRuleService {

    private final BillingRuleMapper ruleMapper;
    private final BillingRuleItemMapper itemMapper;
    private final BillingRuleAuditMapper auditMapper;
    private final BillingScriptEngine scriptEngine;
    private final ObjectMapper objectMapper;
    /**
     * 仅用于 commitInvalidAndAudit 在 REQUIRES_NEW 中提交失败路径的 (status=INVALID + 审计),
     * 这样抛出 EXPRESSION_INVALID 时主流程回滚, 但失败标记会留下, user 还能看到为啥不行.
     */
    private final TransactionTemplate requiresNewTxTemplate;

    /**
     * 显式构造 — 5 个业务 mapper + ObjectMapper + 失败路径专用 TxTemplate (REQUIRES_NEW).
     * 由 Spring 注入 PlatformTransactionManager 后构造 requiresNewTxTemplate.
     */
    public BillingRuleServiceImpl(BillingRuleMapper ruleMapper,
                                  BillingRuleItemMapper itemMapper,
                                  BillingRuleAuditMapper auditMapper,
                                  BillingScriptEngine scriptEngine,
                                  ObjectMapper objectMapper,
                                  PlatformTransactionManager txManager) {
        this.ruleMapper = ruleMapper;
        this.itemMapper = itemMapper;
        this.auditMapper = auditMapper;
        this.scriptEngine = scriptEngine;
        this.objectMapper = objectMapper;
        this.requiresNewTxTemplate = new TransactionTemplate(txManager);
        this.requiresNewTxTemplate.setPropagationBehavior(
            org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---------- create ----------

    @Override
    @Transactional
    public View create(Long tenantId, Long operatorId, CreateRequest req) {
        BillingRule rule = new BillingRule();
        rule.setTenantId(tenantId);
        rule.setBusinessType(req.businessType());
        rule.setDirection(req.direction());
        rule.setCounterpartyType(req.counterpartyType());
        rule.setCurrency(req.currency() != null ? req.currency() : FinanceConstants.CURRENCY_CNY);
        rule.setPriority(req.priority() != null ? req.priority() : 100);
        rule.setStatus(FinanceConstants.RULE_STATUS_DRAFT);
        rule.setEffectiveFrom(req.effectiveFrom());
        rule.setEffectiveTo(req.effectiveTo());
        rule.setRemark(req.remark());
        try {
            ruleMapper.insert(rule);
        } catch (DuplicateKeyException e) {
            log.warn("duplicate billing rule tenant={} biz={} dir={} cp={} from={}",
                tenantId, req.businessType(), req.direction(),
                req.counterpartyType(), req.effectiveFrom());
            throw new FinanceException(FinanceErrorCode.DUPLICATE_RULE);
        }

        // 插入 items
        for (CreateRuleItem item : req.items()) {
            BillingRuleItem ri = new BillingRuleItem();
            ri.setRuleId(rule.getId());
            ri.setSequence(item.sequence());
            ri.setItemType(item.itemType());
            ri.setExpression(item.expression());
            ri.setExpressionHash(BillingScriptEngine.normalizeHash(item.expression()));
            ri.setParamJson(item.paramJson());
            ri.setResultField(item.resultField() != null ? item.resultField() : "gross_amount");
            ri.setRemark(item.remark());
            itemMapper.insert(ri);
        }

        // 写审计 CREATE
        writeAudit(tenantId, rule.getId(), rule.getVersion() == null ? 0 : rule.getVersion(),
            operatorId == null ? 1L : operatorId,
            FinanceConstants.AUDIT_CHANGE_CREATE,
            toJson(Map.of()), "Rule created");

        return toView(rule);
    }

    // ---------- get ----------

    @Override
    public View get(Long tenantId, Long id) {
        BillingRule rule = mustFind(tenantId, id);
        return toView(rule);
    }

    // ---------- update ----------

    @Override
    @Transactional
    public View update(Long tenantId, Long id, Long operatorId, UpdateRequest req) {
        BillingRule rule = mustFind(tenantId, id);
        Map<String, Object> before = snapshot(rule);

        if (req.priority() != null)       rule.setPriority(req.priority());
        if (req.effectiveTo() != null)    rule.setEffectiveTo(req.effectiveTo());
        if (req.remark() != null)         rule.setRemark(req.remark());
        ruleMapper.updateById(rule);

        if (req.items() != null) {
            // 全删全插 items
            itemMapper.delete(new LambdaQueryWrapper<BillingRuleItem>()
                .eq(BillingRuleItem::getRuleId, rule.getId()));
            for (CreateRuleItem item : req.items()) {
                BillingRuleItem ri = new BillingRuleItem();
                ri.setRuleId(rule.getId());
                ri.setSequence(item.sequence());
                ri.setItemType(item.itemType());
                ri.setExpression(item.expression());
                ri.setExpressionHash(BillingScriptEngine.normalizeHash(item.expression()));
                ri.setParamJson(item.paramJson());
                ri.setResultField(item.resultField() != null ? item.resultField() : "gross_amount");
                ri.setRemark(item.remark());
                itemMapper.insert(ri);
            }
        }

        // 写审计 UPDATE，diffJson = { before, after }
        Map<String, Object> after = snapshot(rule);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("before", before);
        diff.put("after", after);
        writeAudit(tenantId, rule.getId(), rule.getVersion() == null ? 0 : rule.getVersion(),
            operatorId == null ? 1L : operatorId,
            FinanceConstants.AUDIT_CHANGE_UPDATE, toJson(diff), "Rule updated");

        return toView(rule);
    }

    // ---------- activate ----------

    @Override
    @Transactional
    public View activate(Long tenantId, Long id, Long operatorId) {
        BillingRule rule = mustFind(tenantId, id);
        // 幂等：已是 ACTIVE 直接返回
        if (FinanceConstants.RULE_STATUS_ACTIVE.equals(rule.getStatus())) {
            return toView(rule);
        }

        // 先按 rule_id + sequence 查询出所有 items（按 seq 升序，方便定位第几条失败）
        List<BillingRuleItem> items = itemMapper.selectList(
            new LambdaQueryWrapper<BillingRuleItem>()
                .eq(BillingRuleItem::getRuleId, rule.getId())
                .orderByAsc(BillingRuleItem::getSequence)
        );

        for (BillingRuleItem item : items) {
            try {
                scriptEngine.compile(item.getExpression());
            } catch (BillingExpressionException bee) {
                log.warn("rule {} item {} compile failed: {}",
                    rule.getId(), item.getSequence(), bee.getMessage());
                // 失败路径: 在 REQUIRES_NEW 独立事务里写 INVALID + audit, 这样后面 throw
                // FinanceException 触发外层事务回滚时, INVALID 标记已经落了库.
                Integer ruleVersion = rule.getVersion() == null ? 0 : rule.getVersion();
                Long opId = operatorId == null ? 1L : operatorId;
                String errMsg = bee.getMessage();
                Integer seq = item.getSequence();
                requiresNewTxTemplate.executeWithoutResult(status ->
                    commitInvalidAndAudit(tenantId, rule.getId(), ruleVersion, opId, seq, errMsg)
                );
                throw new FinanceException(FinanceErrorCode.EXPRESSION_INVALID,
                    "Item " + item.getSequence() + " failed: " + bee.getMessage());
            }
        }

        rule.setStatus(FinanceConstants.RULE_STATUS_ACTIVE);
        ruleMapper.updateById(rule);
        writeAudit(tenantId, rule.getId(), rule.getVersion() == null ? 0 : rule.getVersion(),
            operatorId == null ? 1L : operatorId,
            FinanceConstants.AUDIT_CHANGE_UPDATE,
            toJson(Map.of("status", FinanceConstants.RULE_STATUS_ACTIVE)),
            "Rule activated");

        return toView(rule);
    }

    /**
     * 在 REQUIRES_NEW 事务中写 INVALID 状态 + 审计记录. 该事务会立即提交, 之后外层 throw
     * {@code EXPRESSION_INVALID} 触发主事务回滚时不影响本事务.
     */
    private void commitInvalidAndAudit(Long tenantId, Long ruleId, Integer ruleVersion,
                                       Long operatorId, int sequence, String errMsg) {
        BillingRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            // race condition: 规则被并发删除, 但仍应记录审计异常.
            log.warn("commitInvalidAndAudit: rule {} disappeared", ruleId);
            return;
        }
        rule.setStatus(FinanceConstants.RULE_STATUS_INVALID);
        ruleMapper.updateById(rule);
        writeAudit(tenantId, ruleId, ruleVersion == null ? 0 : ruleVersion,
            operatorId,
            FinanceConstants.AUDIT_CHANGE_UPDATE,
            toJson(Map.of("error", errMsg)),
            "Activation failed at item " + sequence);
    }

    // ---------- disable ----------

    @Override
    @Transactional
    public View disable(Long tenantId, Long id, Long operatorId) {
        BillingRule rule = mustFind(tenantId, id);
        rule.setStatus(FinanceConstants.RULE_STATUS_DISABLED);
        ruleMapper.updateById(rule);

        writeAudit(tenantId, rule.getId(), rule.getVersion() == null ? 0 : rule.getVersion(),
            operatorId == null ? 1L : operatorId,
            FinanceConstants.AUDIT_CHANGE_DISABLE,
            toJson(Map.of("status", FinanceConstants.RULE_STATUS_DISABLED)),
            "Rule disabled");

        return toView(rule);
    }

    // ---------- list ----------

    @Override
    public List<View> list(Long tenantId, String businessType, String direction, String status,
                           Integer pageNum, Integer pageSize) {
        int pn = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int ps = (pageSize == null || pageSize < 1) ? 20 : Math.min(pageSize, 200);
        Page<BillingRule> page = Page.of(pn, ps);

        LambdaQueryWrapper<BillingRule> q = new LambdaQueryWrapper<BillingRule>()
            .eq(BillingRule::getTenantId, tenantId)
            .eq(businessType != null && !businessType.isBlank(), BillingRule::getBusinessType, businessType)
            .eq(direction != null && !direction.isBlank(), BillingRule::getDirection, direction)
            .eq(status != null && !status.isBlank(), BillingRule::getStatus, status)
            .orderByAsc(BillingRule::getPriority)
            .orderByDesc(BillingRule::getId);

        Page<BillingRule> result = ruleMapper.selectPage(page, q);
        List<View> views = new ArrayList<>(result.getRecords().size());
        for (BillingRule r : result.getRecords()) {
            views.add(toView(r));
        }
        return views;
    }

    // ---------- 内部 helper ----------

    private BillingRule mustFind(Long tenantId, Long id) {
        BillingRule rule = ruleMapper.selectById(id);
        if (rule == null || !rule.getTenantId().equals(tenantId)) {
            throw new FinanceException(FinanceErrorCode.RULE_NOT_FOUND);
        }
        return rule;
    }

    /**
     * 默认 REQUIRED 传播: read-only 路径 (get/list) 自动独立提交;
     * 写路径 (create/update/activate) 加入外层事务, 失败回滚一致.
     */
    private View toView(BillingRule rule) {
        List<BillingRuleItem> items = itemMapper.selectList(
            new LambdaQueryWrapper<BillingRuleItem>()
                .eq(BillingRuleItem::getRuleId, rule.getId())
                .orderByAsc(BillingRuleItem::getSequence)
        );
        List<RuleItemView> itemViews = new ArrayList<>(items.size());
        for (BillingRuleItem it : items) {
            itemViews.add(new RuleItemView(
                it.getId(), it.getRuleId(),
                it.getSequence(), it.getItemType(),
                it.getExpression(), it.getExpressionHash(),
                it.getParamJson(), it.getResultField(), it.getRemark()
            ));
        }
        return new View(
            rule.getId(), rule.getTenantId(),
            rule.getBusinessType(), rule.getDirection(), rule.getCounterpartyType(),
            rule.getCurrency(), rule.getPriority(), rule.getStatus(),
            rule.getEffectiveFrom(), rule.getEffectiveTo(), rule.getRemark(),
            itemViews
        );
    }

    /**
     * 审计写入：BillingRuleAudit 字段 fill 由 MyMetaObjectHandler 自动处理 (createdBy 不在表上,
     * operatorId 是显式传入字段).
     */
    private void writeAudit(Long tenantId, Long ruleId, int ruleVersion, Long operatorId,
                            String changeType, String diffJson, String remark) {
        BillingRuleAudit audit = new BillingRuleAudit();
        audit.setTenantId(tenantId);
        audit.setRuleId(ruleId);
        audit.setRuleVersion(ruleVersion);
        audit.setChangeType(changeType);
        audit.setDiffJson(diffJson);
        audit.setOperatorId(operatorId);
        audit.setRemark(remark);
        // changedAt 在 BillingRuleAudit 上是 FieldFill.INSERT, 但 MyMetaObjectHandler 当前
        // 不识别 changedAt (只识别 createdAt/updatedAt/deleted/version/tenantId);
        // 为避免扩展 lumen-common 的基础设施, 直接显式填充.
        audit.setChangedAt(java.time.LocalDateTime.now());
        auditMapper.insert(audit);
    }

    /**
     * rule → JSON-friendly Map (用于 audit diff). 只暴露业务字段，避免泄漏内部字段 (deleted/version/audit 字段).
     */
    private Map<String, Object> snapshot(BillingRule rule) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", rule.getId());
        map.put("businessType", rule.getBusinessType());
        map.put("direction", rule.getDirection());
        map.put("counterpartyType", rule.getCounterpartyType());
        map.put("currency", rule.getCurrency());
        map.put("priority", rule.getPriority());
        map.put("status", rule.getStatus());
        map.put("effectiveFrom", rule.getEffectiveFrom());
        map.put("effectiveTo", rule.getEffectiveTo());
        map.put("remark", rule.getRemark());
        return map;
    }

    /**
     * JSON 序列化。序列化失败一律回退到 {@code "{}"}（避免 audit 写入失败影响主链路）.
     */
    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            log.warn("toJson serialization failed, falling back to empty object", e);
            return "{}";
        }
    }
}
