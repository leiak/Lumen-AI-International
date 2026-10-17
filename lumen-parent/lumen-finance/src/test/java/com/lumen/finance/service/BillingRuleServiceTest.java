package com.lumen.finance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.BillingRuleDtos.CreateRequest;
import com.lumen.finance.dto.BillingRuleDtos.CreateRuleItem;
import com.lumen.finance.dto.BillingRuleDtos.UpdateRequest;
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
import com.lumen.finance.service.impl.BillingRuleServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BillingRuleService 单元测试 (Iteration 4.3.1, PR3-T3.1).
 *
 * <p>纯 Mockito 测试，不启 Spring。所有依赖都 mock 掉。
 *
 * <p>覆盖 8 个核心场景:
 * <ul>
 *   <li>create: 插入 rule + items + audit</li>
 *   <li>create: DuplicateKeyException → DUPLICATE_RULE</li>
 *   <li>get: id 不存在 → RULE_NOT_FOUND</li>
 *   <li>activate: 全部 items 编译成功 → ACTIVE</li>
 *   <li>activate: 单个 item 编译失败 → INVALID + EXPRESSION_INVALID</li>
 *   <li>update: 审计 diffJson 含 before/after snapshot</li>
 *   <li>disable: ACTIVE → DISABLED + 审计 (DISABLE)</li>
 *   <li>list: 过滤条件透传 selectPage</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingRuleService 单元测试")
class BillingRuleServiceTest {

    @Mock private BillingRuleMapper ruleMapper;
    @Mock private BillingRuleItemMapper itemMapper;
    @Mock private BillingRuleAuditMapper auditMapper;
    @Mock private BillingScriptEngine scriptEngine;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private BillingRuleServiceImpl service;

    private static final Long TENANT = 1001L;
    private static final Long OPERATOR = 7777L;

    @BeforeEach
    void setUp() {
        // Use a stub txManager so REQUIRES_NEW branch in activate() works without a real DataSource.
        org.springframework.transaction.PlatformTransactionManager stubTx =
            new org.springframework.transaction.PlatformTransactionManager() {
                @Override public org.springframework.transaction.TransactionStatus getTransaction(
                        org.springframework.transaction.TransactionDefinition definition) {
                    return new org.springframework.transaction.support.SimpleTransactionStatus();
                }
                @Override public void commit(org.springframework.transaction.TransactionStatus status) {}
                @Override public void rollback(org.springframework.transaction.TransactionStatus status) {}
            };
        service = new BillingRuleServiceImpl(ruleMapper, itemMapper, auditMapper,
            scriptEngine, objectMapper, stubTx);
    }

    // ---------- 1. create: insert rule + items + audit ----------

    @Test
    @DisplayName("create: inserts rule + items + audit (CREATE)")
    void create_insertsRuleAndItemsAndAudit() {
        // ruleMapper.insert: 自动回填 id + version + deleted via MyMetaObjectHandler (在 IT 里验证).
        // 这里手动模拟回填:
        when(ruleMapper.insert(any(BillingRule.class))).thenAnswer(inv -> {
            BillingRule r = inv.getArgument(0);
            r.setId(100L);
            r.setVersion(0);
            r.setDeleted(0);
            return 1;
        });

        // itemMapper.insert: 模拟回填 id, 让后续 toView 的 selectList 能拿到
        when(itemMapper.insert(any(BillingRuleItem.class))).thenAnswer(inv -> {
            BillingRuleItem it = inv.getArgument(0);
            it.setId((long) (1000 + it.getSequence()));
            return 1;
        });

        CreateRuleItem i1 = new CreateRuleItem(1, "BASE", "amount * 1.0", null, "gross_amount", null);
        CreateRuleItem i2 = new CreateRuleItem(2, "WEIGHT", "weight * 2", null, "gross_amount", null);
        CreateRequest req = new CreateRequest(
            FinanceConstants.BIZ_LAST_MILE,
            FinanceConstants.DIR_RECEIVABLE,
            FinanceConstants.CP_CUSTOMER,
            FinanceConstants.CURRENCY_CNY, 100,
            LocalDate.of(2026, 10, 1), null, "happy path",
            List.of(i1, i2)
        );

        var view = service.create(TENANT, OPERATOR, req);
        assertThat(view.id()).isEqualTo(100L);
        assertThat(view.status()).isEqualTo(FinanceConstants.RULE_STATUS_DRAFT);

        // verify: rule + 2 items + 1 audit
        verify(ruleMapper, times(1)).insert(any(BillingRule.class));
        verify(itemMapper, times(2)).insert(any(BillingRuleItem.class));
        verify(auditMapper, times(1)).insert(any(BillingRuleAudit.class));

        // verify audit: change_type = CREATE
        ArgumentCaptor<BillingRuleAudit> auditCap = ArgumentCaptor.forClass(BillingRuleAudit.class);
        verify(auditMapper).insert(auditCap.capture());
        assertThat(auditCap.getValue().getChangeType())
            .isEqualTo(FinanceConstants.AUDIT_CHANGE_CREATE);
        assertThat(auditCap.getValue().getOperatorId()).isEqualTo(OPERATOR);
    }

    // ---------- 2. create: DuplicateKeyException → DUPLICATE_RULE ----------

    @Test
    @DisplayName("create: DuplicateKeyException → DUPLICATE_RULE")
    void create_duplicateKey_throwsDuplicateRule() {
        when(ruleMapper.insert(any(BillingRule.class)))
            .thenThrow(new DuplicateKeyException("uk_rule violated"));

        CreateRequest req = new CreateRequest(
            FinanceConstants.BIZ_LAST_MILE,
            FinanceConstants.DIR_RECEIVABLE,
            FinanceConstants.CP_CUSTOMER,
            null, null,
            LocalDate.of(2026, 10, 1), null, null,
            List.of(new CreateRuleItem(1, "BASE", "1.0", null, null, null))
        );

        assertThatThrownBy(() -> service.create(TENANT, OPERATOR, req))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.DUPLICATE_RULE);

        verify(itemMapper, never()).insert(any(BillingRuleItem.class));
        verify(auditMapper, never()).insert(any(BillingRuleAudit.class));
    }

    // ---------- 3. get: id 不存在 → RULE_NOT_FOUND ----------

    @Test
    @DisplayName("get: 不存在的 id → RULE_NOT_FOUND")
    void get_notFound_throwsRuleNotFound() {
        when(ruleMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.get(TENANT, 999L))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.RULE_NOT_FOUND);
    }

    // ---------- 4. activate: 全部 items 编译成功 → ACTIVE ----------

    @Test
    @DisplayName("activate: 全部 items 编译成功 → ACTIVE + audit (UPDATE)")
    void activate_compilesAllItems_succeeds() {
        BillingRule rule = newRule(50L, TENANT, FinanceConstants.RULE_STATUS_DRAFT);
        when(ruleMapper.selectById(50L)).thenReturn(rule);

        BillingRuleItem i1 = itemOf(50L, 1, "amount * 1.0");
        BillingRuleItem i2 = itemOf(50L, 2, "weight * 2");
        when(itemMapper.selectList(any())).thenReturn(List.of(i1, i2));

        // scriptEngine.compile 成功 (无异常)
        var view = service.activate(TENANT, 50L, OPERATOR);

        assertThat(view.status()).isEqualTo(FinanceConstants.RULE_STATUS_ACTIVE);
        verify(scriptEngine, times(2)).compile(any());
        // rule 最后被 update 一次：最后一次 updateById 是设 ACTIVE（之前无 update，因为状态已是 draft → active 一次性切换）
        verify(ruleMapper, times(1)).updateById(any(BillingRule.class));
        // audit 写入 1 条 (UPDATE)
        ArgumentCaptor<BillingRuleAudit> cap = ArgumentCaptor.forClass(BillingRuleAudit.class);
        verify(auditMapper).insert(cap.capture());
        assertThat(cap.getValue().getChangeType()).isEqualTo(FinanceConstants.AUDIT_CHANGE_UPDATE);
    }

    // ---------- 5. activate: 单个 item 编译失败 → INVALID + EXPRESSION_INVALID ----------

    @Test
    @DisplayName("activate: 第 2 个 item 表达式语法错误 → INVALID + EXPRESSION_INVALID")
    void activate_invalidExpression_setsStatusInvalid() {
        BillingRule rule = newRule(60L, TENANT, FinanceConstants.RULE_STATUS_DRAFT);
        when(ruleMapper.selectById(60L)).thenReturn(rule);

        BillingRuleItem i1 = itemOf(60L, 1, "amount * 1.0");
        BillingRuleItem i2 = itemOf(60L, 2, "1 +");  // 语法错误：末尾未完成
        when(itemMapper.selectList(any())).thenReturn(List.of(i1, i2));

        // 模拟：第 1 个 OK，第 2 个抛 EXPRESSION_INVALID
        when(scriptEngine.compile("amount * 1.0")).thenReturn(null); // OK
        when(scriptEngine.compile("1 +")).thenThrow(new BillingExpressionException(
            FinanceErrorCode.EXPRESSION_INVALID, "syntax error: 1 +"));

        assertThatThrownBy(() -> service.activate(TENANT, 60L, OPERATOR))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.EXPRESSION_INVALID);

        // verify: 第 2 次 updateById 把 status 置为 INVALID
        ArgumentCaptor<BillingRule> ruleCap = ArgumentCaptor.forClass(BillingRule.class);
        verify(ruleMapper, times(1)).updateById(ruleCap.capture());
        assertThat(ruleCap.getValue().getStatus()).isEqualTo(FinanceConstants.RULE_STATUS_INVALID);

        // audit 写入了 INVALID 状态记录
        ArgumentCaptor<BillingRuleAudit> auditCap = ArgumentCaptor.forClass(BillingRuleAudit.class);
        verify(auditMapper).insert(auditCap.capture());
        assertThat(auditCap.getValue().getRemark()).contains("Activation failed at item 2");
    }

    // ---------- 6. update: 审计 diffJson 含 before/after snapshot ----------

    @Test
    @DisplayName("update: 审计写入含 before/after snapshot，且 priority 变更被记录")
    void update_writesAuditWithDiffJson() {
        BillingRule rule = newRule(70L, TENANT, FinanceConstants.RULE_STATUS_DRAFT);
        rule.setPriority(100);
        rule.setRemark("old remark");
        when(ruleMapper.selectById(70L)).thenReturn(rule);
        when(itemMapper.selectList(any())).thenReturn(List.of());  // update 不重新走此路径
        // update 重新读
        // rule.items 不变，items() = null 时不重插
        UpdateRequest req = new UpdateRequest(200, null, "new remark", null);

        service.update(TENANT, 70L, OPERATOR, req);

        ArgumentCaptor<BillingRuleAudit> cap = ArgumentCaptor.forClass(BillingRuleAudit.class);
        verify(auditMapper).insert(cap.capture());
        BillingRuleAudit audit = cap.getValue();
        assertThat(audit.getChangeType()).isEqualTo(FinanceConstants.AUDIT_CHANGE_UPDATE);
        assertThat(audit.getDiffJson()).contains("\"priority\":200");
        assertThat(audit.getDiffJson()).contains("\"priority\":100");  // before
        assertThat(audit.getDiffJson()).contains("\"remark\":\"new remark\"");
        assertThat(audit.getDiffJson()).contains("\"remark\":\"old remark\"");
    }

    // ---------- 7. disable: ACTIVE → DISABLED ----------

    @Test
    @DisplayName("disable: ACTIVE → DISABLED + audit (DISABLE)")
    void disable_setsStatusDisabled() {
        BillingRule rule = newRule(80L, TENANT, FinanceConstants.RULE_STATUS_ACTIVE);
        when(ruleMapper.selectById(80L)).thenReturn(rule);
        when(itemMapper.selectList(any())).thenReturn(List.of());

        var view = service.disable(TENANT, 80L, OPERATOR);

        assertThat(view.status()).isEqualTo(FinanceConstants.RULE_STATUS_DISABLED);
        ArgumentCaptor<BillingRule> ruleCap = ArgumentCaptor.forClass(BillingRule.class);
        verify(ruleMapper).updateById(ruleCap.capture());
        assertThat(ruleCap.getValue().getStatus()).isEqualTo(FinanceConstants.RULE_STATUS_DISABLED);

        ArgumentCaptor<BillingRuleAudit> auditCap = ArgumentCaptor.forClass(BillingRuleAudit.class);
        verify(auditMapper).insert(auditCap.capture());
        assertThat(auditCap.getValue().getChangeType())
            .isEqualTo(FinanceConstants.AUDIT_CHANGE_DISABLE);
    }

    // ---------- 8. list: 过滤条件 → selectPage ----------

    @Test
    @DisplayName("list: 过滤条件透传 mapper.selectPage")
    void list_withFilters_callsMapper() {
        // mapper.selectPage 返回空 Page 避免 NPE
        when(ruleMapper.selectPage(any(), any())).thenReturn(
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        var views = service.list(TENANT,
            FinanceConstants.BIZ_LAST_MILE,
            FinanceConstants.DIR_RECEIVABLE,
            FinanceConstants.RULE_STATUS_ACTIVE,
            1, 20);

        assertThat(views).isEmpty();

        verify(ruleMapper, times(1)).selectPage(
            argThat(p -> p.getCurrent() == 1 && p.getSize() == 20),
            any());
    }

    // ---------- 9. activate: 已是 ACTIVE → 直接返回，不写 DB ----------

    @Test
    @DisplayName("activate: 已是 ACTIVE 状态 → 立即返回，无额外 DB 写入")
    void activate_alreadyActive_returnsImmediately_noExtraWrites() {
        BillingRule rule = newRule(50L, TENANT, FinanceConstants.RULE_STATUS_ACTIVE);
        when(ruleMapper.selectById(50L)).thenReturn(rule);

        var view = service.activate(TENANT, 50L, OPERATOR);

        // No compile, no updateById, no audit write
        // (itemMapper.selectList IS called by toView to assemble the view — that's expected)
        verify(scriptEngine, never()).compile(any());
        verify(ruleMapper, never()).updateById(any(BillingRule.class));
        verify(auditMapper, never()).insert(any(BillingRuleAudit.class));
        assertEquals(FinanceConstants.RULE_STATUS_ACTIVE, view.status());
    }

    // ---------- 10. activate: 规则不存在 → RULE_NOT_FOUND ----------

    @Test
    @DisplayName("activate: 规则 id 不存在 → RULE_NOT_FOUND")
    void activate_ruleNotFound_throwsRuleNotFound() {
        when(ruleMapper.selectById(999L)).thenReturn(null);
        var ex = assertThrows(FinanceException.class, () -> service.activate(TENANT, 999L, OPERATOR));
        assertEquals(FinanceErrorCode.RULE_NOT_FOUND, ex.getErrorCode());
    }

    // ---------- 11. disable: 规则不存在 → RULE_NOT_FOUND ----------

    @Test
    @DisplayName("disable: 规则 id 不存在 → RULE_NOT_FOUND")
    void disable_ruleNotFound_throwsRuleNotFound() {
        when(ruleMapper.selectById(999L)).thenReturn(null);
        var ex = assertThrows(FinanceException.class, () -> service.disable(TENANT, 999L, OPERATOR));
        assertEquals(FinanceErrorCode.RULE_NOT_FOUND, ex.getErrorCode());
    }

    // ---------- helpers ----------

    private static BillingRule newRule(long id, long tenantId, String status) {
        BillingRule r = new BillingRule();
        r.setId(id);
        r.setTenantId(tenantId);
        r.setBusinessType(FinanceConstants.BIZ_LAST_MILE);
        r.setDirection(FinanceConstants.DIR_RECEIVABLE);
        r.setCounterpartyType(FinanceConstants.CP_CUSTOMER);
        r.setCurrency(FinanceConstants.CURRENCY_CNY);
        r.setPriority(100);
        r.setStatus(status);
        r.setEffectiveFrom(LocalDate.of(2026, 10, 1));
        r.setVersion(0);
        return r;
    }

    private static BillingRuleItem itemOf(long ruleId, int sequence, String expression) {
        BillingRuleItem i = new BillingRuleItem();
        i.setId((long) (ruleId * 10 + sequence));
        i.setRuleId(ruleId);
        i.setSequence(sequence);
        i.setItemType("BASE");
        i.setExpression(expression);
        i.setExpressionHash(BillingScriptEngine.normalizeHash(expression));
        i.setResultField("gross_amount");
        i.setVersion(0);
        return i;
    }
}
