package com.lumen.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.extension.state.StateMachineEngine;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.entity.BillingAccrual;
import com.lumen.finance.entity.BillingInvoice;
import com.lumen.finance.entity.BillingRule;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingAccrualMapper;
import com.lumen.finance.mapper.BillingInvoiceMapper;
import com.lumen.finance.mapper.BillingRuleMapper;
import com.lumen.finance.service.impl.AccrualServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AccrualService 单元测试 (Iteration 4.3.1, PR3-T3.3).
 *
 * <p>纯 Mockito 测试，不启 Spring。所有依赖都 mock 掉。
 *
 * <p>覆盖 4 个核心场景:
 * <ul>
 *   <li>list: 过滤条件透传 mapper.selectPage</li>
 *   <li>get: id 不存在 / 跨租户 → ACCRUAL_NOT_FOUND</li>
 *   <li>finalize: OPEN → LOCKED → invoice DRAFT → CONVERTED (happy path)</li>
 *   <li>release: OPEN → RELEASED + 状态机校验</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccrualService 单元测试")
class AccrualServiceTest {

    @Mock private BillingAccrualMapper accrualMapper;
    @Mock private BillingInvoiceMapper invoiceMapper;
    @Mock private BillingRuleMapper ruleMapper;
    @Mock private StateMachineEngine stateMachine;

    private AccrualServiceImpl service;

    private static final Long TENANT = 1001L;
    private static final Long OPERATOR = 7777L;

    @BeforeEach
    void setUp() {
        service = new AccrualServiceImpl(accrualMapper, invoiceMapper, ruleMapper, stateMachine);
    }

    // ---------- 1. list: 过滤条件 → selectPage ----------

    @Test
    @DisplayName("list: 过滤条件透传 mapper.selectPage")
    @SuppressWarnings("unchecked")
    void list_withFilters_callsMapper() {
        when(accrualMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
            .thenReturn(new Page<BillingAccrual>());

        var rows = service.list(TENANT,
            FinanceConstants.BIZ_LAST_MILE,
            FinanceConstants.ACCRUAL_STATUS_OPEN,
            1, 20);

        assertThat(rows).isEmpty();

        verify(accrualMapper, times(1)).selectPage(
            argThat((Page<BillingAccrual> p) -> p.getCurrent() == 1 && p.getSize() == 20),
            any(LambdaQueryWrapper.class));
    }

    // ---------- 2. get: 不存在 / 跨租户 → ACCRUAL_NOT_FOUND ----------

    @Test
    @DisplayName("get: accrual 不存在 / 跨租户 → ACCRUAL_NOT_FOUND")
    void get_notFound_throwsAccrualNotFound() {
        // 不存在
        when(accrualMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> service.get(TENANT, 999L))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.ACCRUAL_NOT_FOUND);

        // 跨租户: selectById 返回了但 tenantId 不匹配
        BillingAccrual otherTenant = new BillingAccrual();
        otherTenant.setId(100L);
        otherTenant.setTenantId(9999L);  // 不同于 TENANT
        when(accrualMapper.selectById(100L)).thenReturn(otherTenant);
        assertThatThrownBy(() -> service.get(TENANT, 100L))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.ACCRUAL_NOT_FOUND);
    }

    // ---------- 3. finalize: OPEN → LOCKED → invoice DRAFT → CONVERTED ----------

    @Test
    @DisplayName("finalize: OPEN accrual → LOCKED → invoice DRAFT → CONVERTED")
    void finalize_openAccrual_succeeds() {
        // arrange
        BillingAccrual accrual = newAccrual(50L, TENANT, FinanceConstants.ACCRUAL_STATUS_OPEN);
        accrual.setDirection(FinanceConstants.DIR_RECEIVABLE);
        accrual.setCounterpartyId(888L);
        accrual.setRuleId(20L);
        accrual.setGrossAmount(new BigDecimal("123.45"));
        accrual.setTaxAmount(new BigDecimal("0"));
        accrual.setNetAmount(new BigDecimal("123.45"));
        accrual.setCurrency(FinanceConstants.CURRENCY_CNY);
        when(accrualMapper.selectById(50L)).thenReturn(accrual);

        BillingRule rule = newRule(20L, TENANT, FinanceConstants.CP_CUSTOMER);
        rule.setVersion(7);
        when(ruleMapper.selectById(20L)).thenReturn(rule);

        // 关键: accrual 是同一对象引用, 2 次 updateById 都共享同一个实例.
        // 用 doAnswer 在每次调用时记录当时的状态快照 (而不是 ArgumentCaptor, 因为 captor 抓到的是引用).
        List<String> accrualStatusesAtUpdate = new ArrayList<>();
        List<java.time.LocalDateTime> accrualLockedAtsAtUpdate = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            BillingAccrual a = inv.getArgument(0);
            accrualStatusesAtUpdate.add(a.getStatus());
            accrualLockedAtsAtUpdate.add(a.getLockedAt());
            return 1;
        }).when(accrualMapper).updateById(any(BillingAccrual.class));

        // mock invoice insert 回填 id
        when(invoiceMapper.insert(any(BillingInvoice.class))).thenAnswer(inv -> {
            BillingInvoice inv0 = inv.getArgument(0);
            inv0.setId(700L);
            return 1;
        });

        // act
        BillingInvoice invoice = service.finalize(TENANT, 50L, OPERATOR);

        // assert: 状态机 2 次校验都被调用 (OPEN→LOCKED, LOCKED→CONVERTED)
        verify(stateMachine, times(1)).assertTransition(
            FinanceConstants.SM_BILLING_ACCRUAL,
            FinanceConstants.ACCRUAL_STATUS_OPEN,
            FinanceConstants.ACCRUAL_STATUS_LOCKED);
        verify(stateMachine, times(1)).assertTransition(
            FinanceConstants.SM_BILLING_ACCRUAL,
            FinanceConstants.ACCRUAL_STATUS_LOCKED,
            FinanceConstants.ACCRUAL_STATUS_CONVERTED);

        // assert: invoice 已插入, 字段透传
        ArgumentCaptor<BillingInvoice> invCap = ArgumentCaptor.forClass(BillingInvoice.class);
        verify(invoiceMapper, times(1)).insert(invCap.capture());
        BillingInvoice inserted = invCap.getValue();
        assertThat(inserted.getTenantId()).isEqualTo(TENANT);
        assertThat(inserted.getSourceAccrualId()).isEqualTo(50L);
        assertThat(inserted.getRuleId()).isEqualTo(20L);
        assertThat(inserted.getRuleVersion()).isEqualTo(7);
        assertThat(inserted.getCounterpartyType()).isEqualTo(FinanceConstants.CP_CUSTOMER);
        assertThat(inserted.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_DRAFT);
        assertThat(inserted.getDirection()).isEqualTo(FinanceConstants.DIR_RECEIVABLE);
        assertThat(inserted.getGrossAmount()).isEqualByComparingTo(new BigDecimal("123.45"));
        assertThat(inserted.getNetAmount()).isEqualByComparingTo(new BigDecimal("123.45"));
        assertThat(inserted.getInvoiceNo()).startsWith("AR-").contains("-" + TENANT + "-");
        // AR-{tenantId}-{epochMs}-{seq} 4 段
        assertThat(inserted.getInvoiceNo().split("-")).hasSize(4);

        // assert: 返回的 invoice 是同一对象 (id 已回填)
        assertThat(invoice).isNotNull();
        assertThat(invoice.getId()).isEqualTo(700L);
        assertThat(invoice.getInvoiceNo()).isEqualTo(inserted.getInvoiceNo());

        // assert: accrual 2 次 update, 顺序为 LOCKED → CONVERTED
        assertThat(accrualStatusesAtUpdate).containsExactly(
            FinanceConstants.ACCRUAL_STATUS_LOCKED,
            FinanceConstants.ACCRUAL_STATUS_CONVERTED);
        // lockedAt 在第 1 次 update 时被设置, 第 2 次时还在
        assertThat(accrualLockedAtsAtUpdate.get(0)).isNotNull();
        assertThat(accrualLockedAtsAtUpdate.get(1)).isNotNull();
    }

    // ---------- 4. release: OPEN → RELEASED ----------

    @Test
    @DisplayName("release: OPEN accrual → RELEASED + 状态机校验 + errorMsg")
    void release_openAccrual_succeeds() {
        BillingAccrual accrual = newAccrual(60L, TENANT, FinanceConstants.ACCRUAL_STATUS_OPEN);
        when(accrualMapper.selectById(60L)).thenReturn(accrual);

        BillingAccrual released = service.release(TENANT, 60L, "moved to next period", OPERATOR);

        // 状态机校验 1 次 (OPEN → RELEASED)
        verify(stateMachine, times(1)).assertTransition(
            FinanceConstants.SM_BILLING_ACCRUAL,
            FinanceConstants.ACCRUAL_STATUS_OPEN,
            FinanceConstants.ACCRUAL_STATUS_RELEASED);

        // accrual update 1 次: RELEASED + errorMsg
        ArgumentCaptor<BillingAccrual> accCap = ArgumentCaptor.forClass(BillingAccrual.class);
        verify(accrualMapper, times(1)).updateById(accCap.capture());
        assertThat(accCap.getValue().getStatus()).isEqualTo(FinanceConstants.ACCRUAL_STATUS_RELEASED);
        assertThat(accCap.getValue().getErrorMsg()).isEqualTo("moved to next period");

        // invoiceMapper 不应被调用
        verify(invoiceMapper, never()).insert(any(BillingInvoice.class));

        assertThat(released.getStatus()).isEqualTo(FinanceConstants.ACCRUAL_STATUS_RELEASED);
        assertThat(released.getErrorMsg()).isEqualTo("moved to next period");
    }

    // ---------- 5. finalize: 非 OPEN 状态 → INVALID_STATE_TRANSITION ----------

    @Test
    @DisplayName("finalize: 非 OPEN accrual (LOCKED) → INVALID_STATE_TRANSITION, 不写库")
    void finalize_nonOpenStatus_throwsInvalidStateTransition() {
        var accrual = new com.lumen.finance.entity.BillingAccrual();
        accrual.setId(100L);
        accrual.setTenantId(TENANT);
        accrual.setStatus(FinanceConstants.ACCRUAL_STATUS_LOCKED);  // already locked
        when(accrualMapper.selectById(100L)).thenReturn(accrual);

        FinanceException ex = assertThrows(FinanceException.class,
            () -> service.finalize(TENANT, 100L, OPERATOR));
        assertEquals(FinanceErrorCode.INVALID_STATE_TRANSITION, ex.getErrorCode());
        verify(invoiceMapper, never()).insert(any(BillingInvoice.class));
        verify(stateMachine, never()).assertTransition(any(), any(), any());
    }

    // ---------- 6. release: 非 OPEN 状态 → INVALID_STATE_TRANSITION ----------

    @Test
    @DisplayName("release: 非 OPEN accrual (CONVERTED) → INVALID_STATE_TRANSITION, 不调用状态机")
    void release_nonOpenStatus_throwsInvalidStateTransition() {
        var accrual = new com.lumen.finance.entity.BillingAccrual();
        accrual.setId(100L);
        accrual.setTenantId(TENANT);
        accrual.setStatus(FinanceConstants.ACCRUAL_STATUS_CONVERTED);  // already converted
        when(accrualMapper.selectById(100L)).thenReturn(accrual);

        FinanceException ex = assertThrows(FinanceException.class,
            () -> service.release(TENANT, 100L, "test reason", OPERATOR));
        assertEquals(FinanceErrorCode.INVALID_STATE_TRANSITION, ex.getErrorCode());
        verify(stateMachine, never()).assertTransition(any(), any(), any());
        verify(accrualMapper, never()).updateById(any(BillingAccrual.class));
    }

    // ---------- 7. finalize: 并发 updateById 失败 → INVALID_STATE_TRANSITION ----------

    @Test
    @DisplayName("finalize: 并发 finalize 触发乐观锁 → INVALID_STATE_TRANSITION")
    void finalize_concurrentUpdate_throwsInvalidStateTransition() {
        var accrual = new com.lumen.finance.entity.BillingAccrual();
        accrual.setId(100L);
        accrual.setTenantId(TENANT);
        accrual.setStatus(FinanceConstants.ACCRUAL_STATUS_OPEN);
        when(accrualMapper.selectById(100L)).thenReturn(accrual);

        // 第 1 次 updateById 成功 (OPEN→LOCKED), 第 2 次抛乐观锁异常
        when(accrualMapper.updateById(any(BillingAccrual.class)))
            .thenReturn(1)
            .thenThrow(new org.springframework.dao.OptimisticLockingFailureException("concurrent"));

        var rule = new com.lumen.finance.entity.BillingRule();
        rule.setId(100L);
        rule.setCounterpartyType(FinanceConstants.CP_CUSTOMER);
        rule.setVersion(1);
        when(ruleMapper.selectById(any())).thenReturn(rule);
        when(invoiceMapper.insert(any(BillingInvoice.class))).thenReturn(1);

        FinanceException ex = assertThrows(FinanceException.class,
            () -> service.finalize(TENANT, 100L, OPERATOR));
        assertEquals(FinanceErrorCode.INVALID_STATE_TRANSITION, ex.getErrorCode());
    }

    // ---------- helpers ----------

    private static BillingAccrual newAccrual(long id, long tenantId, String status) {
        BillingAccrual a = new BillingAccrual();
        a.setId(id);
        a.setTenantId(tenantId);
        a.setSourceEventId("evt-" + id);
        a.setBusinessType(FinanceConstants.BIZ_LAST_MILE);
        a.setDirection(FinanceConstants.DIR_RECEIVABLE);
        a.setCounterpartyId(100L);
        a.setGrossAmount(new BigDecimal("100.00"));
        a.setTaxAmount(BigDecimal.ZERO);
        a.setNetAmount(new BigDecimal("100.00"));
        a.setCurrency(FinanceConstants.CURRENCY_CNY);
        a.setStatus(status);
        a.setVersion(0);
        return a;
    }

    private static BillingRule newRule(long id, long tenantId, String counterpartyType) {
        BillingRule r = new BillingRule();
        r.setId(id);
        r.setTenantId(tenantId);
        r.setBusinessType(FinanceConstants.BIZ_LAST_MILE);
        r.setDirection(FinanceConstants.DIR_RECEIVABLE);
        r.setCounterpartyType(counterpartyType);
        r.setCurrency(FinanceConstants.CURRENCY_CNY);
        r.setStatus(FinanceConstants.RULE_STATUS_ACTIVE);
        r.setPriority(100);
        r.setEffectiveFrom(LocalDate.of(2026, 10, 1));
        r.setVersion(0);
        return r;
    }
}
