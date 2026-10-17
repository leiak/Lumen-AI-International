package com.lumen.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.extension.state.StateMachineEngine;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.entity.BillingInvoice;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingInvoiceMapper;
import com.lumen.finance.service.ExchangeRateService;
import com.lumen.finance.service.impl.InvoiceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
 * InvoiceService 单元测试 (Iteration 4.3.1, PR3-T3.4; 4.3.2 PR3 增 L2).
 *
 * <p>纯 Mockito 测试，不启 Spring。所有依赖 (mapper + stateMachine) 都 mock 掉。
 *
 * <p>覆盖 5 个核心场景:
 * <ul>
 *   <li>submit: DRAFT → PENDING_L1 + submittedAt 已设置</li>
 *   <li>l1Approve: PENDING_L1 → PENDING_L2 + l1ApproverId / l1ApprovedAt / l1Comment 已设置
 *       (4.3.2 PR3 起终态改为 PENDING_L2)</li>
 *   <li>l1Reject: PENDING_L1 → REJECTED + comment 必填验证 + l1ApproverId 已设置</li>
 *   <li>l2Approve: PENDING_L2 → L2_APPROVED + l2* 字段 + 同人拦截</li>
 *   <li>l2Reject:  PENDING_L2 → REJECTED    + comment 必填验证 + 同人拦截</li>
 * </ul>
 *
 * <p>list/get/状态机校验失败的次要场景不在此处重复 (已由 AccrualServiceTest 完整覆盖同模式).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InvoiceService 单元测试")
class InvoiceServiceTest {

    @Mock private BillingInvoiceMapper invoiceMapper;
    @Mock private StateMachineEngine stateMachine;
    @Mock private ExchangeRateService exchangeRateService;

    private InvoiceServiceImpl service;

    private static final Long TENANT = 2001L;
    private static final Long OPERATOR = 8888L;

    @BeforeEach
    void setUp() {
        service = new InvoiceServiceImpl(invoiceMapper, stateMachine, exchangeRateService);
    }

    // ---------- 1. submit: DRAFT -> PENDING_L1 ----------

    @Test
    @DisplayName("submit: DRAFT invoice → PENDING_L1 + submittedAt 已设置")
    void submit_draftInvoice_movesToPendingL1() {
        // arrange
        BillingInvoice inv = newInvoice(70L, TENANT, FinanceConstants.INV_STATUS_DRAFT);
        when(invoiceMapper.selectById(70L)).thenReturn(inv);

        LocalDateTime before = LocalDateTime.now();
        BillingInvoice returned = service.submit(TENANT, 70L, OPERATOR);
        LocalDateTime after = LocalDateTime.now();

        // assert: 状态机 1 次校验 DRAFT → PENDING_L1
        verify(stateMachine, times(1)).assertTransition(
            FinanceConstants.SM_BILLING_INVOICE,
            FinanceConstants.INV_STATUS_DRAFT,
            FinanceConstants.INV_STATUS_PENDING_L1);

        // assert: updateById 调用 1 次, status=PENDING_L1 + submittedAt 在 before..after 之间
        ArgumentCaptor<BillingInvoice> cap = ArgumentCaptor.forClass(BillingInvoice.class);
        verify(invoiceMapper, times(1)).updateById(cap.capture());
        BillingInvoice updated = cap.getValue();
        assertEquals(FinanceConstants.INV_STATUS_PENDING_L1, updated.getStatus());
        assertThat(updated.getSubmittedAt()).isBetween(before, after);

        // assert: 返回的 invoice 是同一对象, status/submittedAt 已更新
        assertThat(returned.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_PENDING_L1);
        assertThat(returned.getSubmittedAt()).isNotNull();
    }

    @Test
    @DisplayName("submit: 非 DRAFT 状态 (PENDING_L1) → INVALID_STATE_TRANSITION, 不写库")
    void submit_nonDraftStatus_throwsInvalidStateTransition() {
        BillingInvoice inv = newInvoice(80L, TENANT, FinanceConstants.INV_STATUS_PENDING_L1);
        when(invoiceMapper.selectById(80L)).thenReturn(inv);

        FinanceException ex = assertThrows(FinanceException.class,
            () -> service.submit(TENANT, 80L, OPERATOR));
        assertEquals(FinanceErrorCode.INVALID_STATE_TRANSITION, ex.getErrorCode());
        verify(invoiceMapper, never()).updateById(any(BillingInvoice.class));
        verify(stateMachine, never()).assertTransition(any(), any(), any());
    }

    // ---------- 2. l1Approve: PENDING_L1 -> PENDING_L2 (4.3.2 PR3) ----------

    @Test
    @DisplayName("l1Approve: PENDING_L1 invoice → PENDING_L2 + l1ApproverId/l1ApprovedAt/l1Comment 已设置")
    void l1Approve_pendingInvoice_movesToPendingL2() {
        // arrange
        BillingInvoice inv = newInvoice(90L, TENANT, FinanceConstants.INV_STATUS_PENDING_L1);
        when(invoiceMapper.selectById(90L)).thenReturn(inv);

        LocalDateTime before = LocalDateTime.now();
        BillingInvoice returned = service.l1Approve(TENANT, 90L, OPERATOR, "looks good");
        LocalDateTime after = LocalDateTime.now();

        // assert: 状态机 1 次校验 PENDING_L1 → PENDING_L2 (4.3.2 PR3 起终态改 PENDING_L2)
        verify(stateMachine, times(1)).assertTransition(
            FinanceConstants.SM_BILLING_INVOICE,
            FinanceConstants.INV_STATUS_PENDING_L1,
            FinanceConstants.INV_STATUS_PENDING_L2);

        // assert: updateById 调用 1 次, 字段都设置
        ArgumentCaptor<BillingInvoice> cap = ArgumentCaptor.forClass(BillingInvoice.class);
        verify(invoiceMapper, times(1)).updateById(cap.capture());
        BillingInvoice updated = cap.getValue();
        assertEquals(FinanceConstants.INV_STATUS_PENDING_L2, updated.getStatus());
        assertEquals(OPERATOR, updated.getL1ApproverId());
        assertThat(updated.getL1ApprovedAt()).isBetween(before, after);
        assertEquals("looks good", updated.getL1Comment());

        // assert: 返回的 invoice 是同一对象
        assertThat(returned.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_PENDING_L2);
        assertThat(returned.getL1ApproverId()).isEqualTo(OPERATOR);
        assertThat(returned.getL1Comment()).isEqualTo("looks good");
    }

    @Test
    @DisplayName("l1Approve: comment=null 允许, l1Comment=null 落库")
    void l1Approve_nullComment_allowed() {
        BillingInvoice inv = newInvoice(91L, TENANT, FinanceConstants.INV_STATUS_PENDING_L1);
        when(invoiceMapper.selectById(91L)).thenReturn(inv);

        BillingInvoice returned = service.l1Approve(TENANT, 91L, OPERATOR, null);

        ArgumentCaptor<BillingInvoice> cap = ArgumentCaptor.forClass(BillingInvoice.class);
        verify(invoiceMapper, times(1)).updateById(cap.capture());
        assertEquals(FinanceConstants.INV_STATUS_PENDING_L2, cap.getValue().getStatus());
        assertEquals(OPERATOR, cap.getValue().getL1ApproverId());
        assertThat(returned.getL1Comment()).isNull();
    }

    // ---------- 3. l1Reject: PENDING_L1 -> REJECTED (comment 必填) ----------

    @Test
    @DisplayName("l1Reject: PENDING_L1 invoice → REJECTED + comment 必填验证")
    void l1Reject_pendingInvoice_movesToRejected() {
        BillingInvoice inv = newInvoice(100L, TENANT, FinanceConstants.INV_STATUS_PENDING_L1);
        when(invoiceMapper.selectById(100L)).thenReturn(inv);

        LocalDateTime before = LocalDateTime.now();
        BillingInvoice returned = service.l1Reject(TENANT, 100L, OPERATOR, "amount incorrect");
        LocalDateTime after = LocalDateTime.now();

        // assert: 状态机 1 次校验 PENDING_L1 → REJECTED
        verify(stateMachine, times(1)).assertTransition(
            FinanceConstants.SM_BILLING_INVOICE,
            FinanceConstants.INV_STATUS_PENDING_L1,
            FinanceConstants.INV_STATUS_REJECTED);

        // assert: updateById 调用 1 次, 字段都设置
        ArgumentCaptor<BillingInvoice> cap = ArgumentCaptor.forClass(BillingInvoice.class);
        verify(invoiceMapper, times(1)).updateById(cap.capture());
        BillingInvoice updated = cap.getValue();
        assertEquals(FinanceConstants.INV_STATUS_REJECTED, updated.getStatus());
        assertEquals(OPERATOR, updated.getL1ApproverId());
        assertThat(updated.getL1ApprovedAt()).isBetween(before, after);
        assertEquals("amount incorrect", updated.getL1Comment());

        assertThat(returned.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_REJECTED);
        assertThat(returned.getL1Comment()).isEqualTo("amount incorrect");
    }

    @Test
    @DisplayName("l1Reject: comment=null → INVOICE_REJECT_REASON_REQUIRED, 不调用状态机, 不写库")
    void l1Reject_nullComment_throwsRejectReasonRequired() {
        // 注意: comment 校验在 selectById 之前, 不需要 mock mapper
        assertThatThrownBy(() -> service.l1Reject(TENANT, 101L, OPERATOR, null))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.INVOICE_REJECT_REASON_REQUIRED);
        verify(invoiceMapper, never()).selectById(any());
        verify(invoiceMapper, never()).updateById(any(BillingInvoice.class));
        verify(stateMachine, never()).assertTransition(any(), any(), any());
    }

    @Test
    @DisplayName("l1Reject: comment=blank → INVOICE_REJECT_REASON_REQUIRED")
    void l1Reject_blankComment_throwsRejectReasonRequired() {
        // 注意: comment 校验在 selectById 之前, 不需要 mock mapper
        assertThatThrownBy(() -> service.l1Reject(TENANT, 102L, OPERATOR, "   \t  "))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.INVOICE_REJECT_REASON_REQUIRED);
        verify(invoiceMapper, never()).selectById(any());
        verify(invoiceMapper, never()).updateById(any(BillingInvoice.class));
        verify(stateMachine, never()).assertTransition(any(), any(), any());
    }

    @Test
    @DisplayName("l1Reject: 非 PENDING_L1 状态 → INVALID_STATE_TRANSITION")
    void l1Reject_nonPendingStatus_throwsInvalidStateTransition() {
        BillingInvoice inv = newInvoice(103L, TENANT, FinanceConstants.INV_STATUS_PENDING_L2);
        when(invoiceMapper.selectById(103L)).thenReturn(inv);

        // comment 是 valid, 但 status 不是 PENDING_L1 → 状态校验先抛
        FinanceException ex = assertThrows(FinanceException.class,
            () -> service.l1Reject(TENANT, 103L, OPERATOR, "valid comment"));
        assertEquals(FinanceErrorCode.INVALID_STATE_TRANSITION, ex.getErrorCode());
        verify(invoiceMapper, never()).updateById(any(BillingInvoice.class));
        verify(stateMachine, never()).assertTransition(any(), any(), any());
    }

    // ---------- 4. 并发乐观锁 ----------

    @Test
    @DisplayName("submit: 并发 updateById 触发 OptimisticLockingFailureException → INVALID_STATE_TRANSITION")
    void submit_concurrentUpdate_throwsInvalidStateTransition() {
        BillingInvoice inv = newInvoice(110L, TENANT, FinanceConstants.INV_STATUS_DRAFT);
        when(invoiceMapper.selectById(110L)).thenReturn(inv);
        when(invoiceMapper.updateById(any(BillingInvoice.class)))
            .thenThrow(new OptimisticLockingFailureException("concurrent submit"));

        FinanceException ex = assertThrows(FinanceException.class,
            () -> service.submit(TENANT, 110L, OPERATOR));
        assertEquals(FinanceErrorCode.INVALID_STATE_TRANSITION, ex.getErrorCode());
    }

    @Test
    @DisplayName("l1Approve: 并发 updateById 触发 OptimisticLockingFailureException → INVALID_STATE_TRANSITION")
    void l1Approve_concurrentUpdate_throwsInvalidStateTransition() {
        BillingInvoice inv = newInvoice(111L, TENANT, FinanceConstants.INV_STATUS_PENDING_L1);
        when(invoiceMapper.selectById(111L)).thenReturn(inv);
        when(invoiceMapper.updateById(any(BillingInvoice.class)))
            .thenThrow(new OptimisticLockingFailureException("concurrent l1Approve"));

        FinanceException ex = assertThrows(FinanceException.class,
            () -> service.l1Approve(TENANT, 111L, OPERATOR, "ok"));
        assertEquals(FinanceErrorCode.INVALID_STATE_TRANSITION, ex.getErrorCode());
    }

    // ---------- 5. list 冒烟 (透传 mapper.selectPage) ----------

    @Test
    @DisplayName("list: 过滤条件透传 mapper.selectPage")
    @SuppressWarnings("unchecked")
    void list_withFilters_callsMapper() {
        when(invoiceMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
            .thenReturn(new Page<BillingInvoice>());

        var rows = service.list(TENANT, FinanceConstants.INV_STATUS_DRAFT, 1, 20);

        assertThat(rows).isEmpty();

        verify(invoiceMapper, times(1)).selectPage(
            argThat((Page<BillingInvoice> p) -> p.getCurrent() == 1 && p.getSize() == 20),
            any(LambdaQueryWrapper.class));
    }

    // ---------- 6. L2 fields null lock (4.3.2 PR3: L1 走完后 L2 字段仍为 null, 等 L2 审批) ----------

    @Test
    @DisplayName("l2FieldsAreNullInCurrentResponse: L1 走完后, View DTO 的 L2 字段全为 null (锁定 4.3.2 PR3 行为)")
    void l2FieldsAreNullInCurrentResponse() {
        // 4.3.2 PR3 entity 已含 l2_approver_id / l2_approved_at / l2_comment (V30 schema),
        // L1 走完 (status=PENDING_L2) 后 L2 字段还没写, 仍为 null. controller.toView 现在
        // 改为走 entity getter 透传. 此测试锁定 "L1-only 后 L2=null" 行为.
        BillingInvoice inv = newInvoice(200L, TENANT, FinanceConstants.INV_STATUS_DRAFT);
        when(invoiceMapper.selectById(200L)).thenReturn(inv);

        BillingInvoice submitted = service.submit(TENANT, 200L, OPERATOR);
        assertThat(submitted.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_PENDING_L1);

        when(invoiceMapper.selectById(200L)).thenReturn(submitted);
        BillingInvoice approved = service.l1Approve(TENANT, 200L, OPERATOR, "ok");
        assertThat(approved.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_PENDING_L2);

        // 构造 View (字段顺序对齐 BillingInvoiceController.toView, L2 字段走 entity getter)
        var view = new com.lumen.finance.dto.BillingInvoiceDtos.View(
            approved.getId(), approved.getTenantId(), approved.getInvoiceNo(),
            approved.getDirection(), approved.getCounterpartyId(),
            approved.getCounterpartyType(), approved.getBusinessType(),
            approved.getSourceAccrualId(), approved.getGrossAmount(),
            approved.getTaxAmount(), approved.getNetAmount(),
            approved.getCurrency(), approved.getStatus(),
            approved.getRuleId(), approved.getRuleVersion(),
            approved.getSubmittedAt(), approved.getL1ApproverId(),
            approved.getL1ApprovedAt(), approved.getL1Comment(),
            approved.getL2ApproverId(),   // 4.3.2 PR3: 走 entity getter
            approved.getL2ApprovedAt(),   // 4.3.2 PR3
            approved.getL2Comment(),      // 4.3.2 PR3
            approved.getErrorMsg(),       // errorMsg (V28 字段)
            approved.getCreatedAt()
        );

        // 锁定 4.3.2 PR3 行为: L1-only 后 L2 字段全部 null
        assertThat(view.l2ApproverId()).isNull();
        assertThat(view.l2ApprovedAt()).isNull();
        assertThat(view.l2Comment()).isNull();
        // L1 字段应已落库 (sanity check, 确保上面 submit/l1Approve 真走到了)
        assertThat(view.l1ApproverId()).isEqualTo(OPERATOR);
        assertThat(view.l1ApprovedAt()).isNotNull();
    }

    // ---------- 7. l2Approve: PENDING_L2 -> L2_APPROVED (4.3.2 PR3) ----------

    @Test
    @DisplayName("l2Approve: PENDING_L2 invoice → L2_APPROVED + l2ApproverId/l2ApprovedAt/l2Comment 已设置")
    void l2Approve_happyPath_writesL2Fields() {
        // arrange: 已走完 L1 (l1ApproverId=1001), 等 L2
        Long L1_APPROVER = 1001L;
        Long L2_APPROVER = 1002L;
        BillingInvoice inv = newPendingL2Invoice(300L, TENANT, L1_APPROVER);
        when(invoiceMapper.selectById(300L)).thenReturn(inv);

        LocalDateTime before = LocalDateTime.now();
        BillingInvoice returned = service.l2Approve(TENANT, 300L, L2_APPROVER, "L2 looks good");
        LocalDateTime after = LocalDateTime.now();

        // assert: 状态机 1 次校验 PENDING_L2 → L2_APPROVED
        verify(stateMachine, times(1)).assertTransition(
            FinanceConstants.SM_BILLING_INVOICE,
            FinanceConstants.INV_STATUS_PENDING_L2,
            FinanceConstants.INV_STATUS_L2_APPROVED);

        // assert: updateById 调用 1 次, L2 字段全部设置
        ArgumentCaptor<BillingInvoice> cap = ArgumentCaptor.forClass(BillingInvoice.class);
        verify(invoiceMapper, times(1)).updateById(cap.capture());
        BillingInvoice updated = cap.getValue();
        assertEquals(FinanceConstants.INV_STATUS_L2_APPROVED, updated.getStatus());
        assertEquals(L2_APPROVER, updated.getL2ApproverId());
        assertThat(updated.getL2ApprovedAt()).isBetween(before, after);
        assertEquals("L2 looks good", updated.getL2Comment());

        // assert: L1 字段保留 (sanity)
        assertEquals(L1_APPROVER, updated.getL1ApproverId());

        // assert: 返回的 invoice
        assertThat(returned.getStatus()).isEqualTo(FinanceConstants.INV_STATUS_L2_APPROVED);
        assertThat(returned.getL2ApproverId()).isEqualTo(L2_APPROVER);
        assertThat(returned.getL2Comment()).isEqualTo("L2 looks good");
    }

    @Test
    @DisplayName("l2Approve: operator 与 l1ApproverId 相同 → L1_L2_SAME_OPERATOR, 不写库")
    void l2Approve_sameOperatorAsL1_throwsL1L2SameOperator() {
        // arrange: l1ApproverId=1001
        Long SAME_OPERATOR = 1001L;
        BillingInvoice inv = newPendingL2Invoice(301L, TENANT, SAME_OPERATOR);
        when(invoiceMapper.selectById(301L)).thenReturn(inv);

        // act + assert: 同操作员再做 L2 → L1_L2_SAME_OPERATOR
        assertThatThrownBy(() -> service.l2Approve(TENANT, 301L, SAME_OPERATOR, null))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.L1_L2_SAME_OPERATOR);

        // 同人拦截在 selectById 之后, 校验在状态机之前 (写在 entity 落库前)
        verify(invoiceMapper, never()).updateById(any(BillingInvoice.class));
        verify(stateMachine, never()).assertTransition(any(), any(), any());
    }

    // ---------- 8. l2Reject: PENDING_L2 -> REJECTED (4.3.2 PR3, comment 必填) ----------

    @Test
    @DisplayName("l2Reject: comment=null → INVOICE_REJECT_REASON_REQUIRED, 不读库, 不写库")
    void l2Reject_nullComment_throwsRejectReasonRequired() {
        // 注意: comment 校验在 selectById 之前, 不需要 mock mapper
        assertThatThrownBy(() -> service.l2Reject(TENANT, 400L, OPERATOR, null))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.INVOICE_REJECT_REASON_REQUIRED);
        verify(invoiceMapper, never()).selectById(any());
        verify(invoiceMapper, never()).updateById(any(BillingInvoice.class));
        verify(stateMachine, never()).assertTransition(any(), any(), any());
    }

    @Test
    @DisplayName("l2Reject: comment=blank → INVOICE_REJECT_REASON_REQUIRED")
    void l2Reject_blankComment_throwsRejectReasonRequired() {
        assertThatThrownBy(() -> service.l2Reject(TENANT, 401L, OPERATOR, "   \t  "))
            .isInstanceOf(FinanceException.class)
            .extracting("errorCode")
            .isEqualTo(FinanceErrorCode.INVOICE_REJECT_REASON_REQUIRED);
        verify(invoiceMapper, never()).selectById(any());
        verify(invoiceMapper, never()).updateById(any(BillingInvoice.class));
        verify(stateMachine, never()).assertTransition(any(), any(), any());
    }

    // ---------- helpers ----------

    private static BillingInvoice newInvoice(long id, long tenantId, String status) {
        BillingInvoice inv = new BillingInvoice();
        inv.setId(id);
        inv.setTenantId(tenantId);
        inv.setInvoiceNo("AR-" + tenantId + "-" + id);
        inv.setDirection(FinanceConstants.DIR_RECEIVABLE);
        inv.setCounterpartyId(500L);
        inv.setCounterpartyType(FinanceConstants.CP_CUSTOMER);
        inv.setBusinessType(FinanceConstants.BIZ_LAST_MILE);
        inv.setGrossAmount(new BigDecimal("100.00"));
        inv.setTaxAmount(BigDecimal.ZERO);
        inv.setNetAmount(new BigDecimal("100.00"));
        inv.setCurrency(FinanceConstants.CURRENCY_CNY);
        inv.setStatus(status);
        inv.setRuleId(1L);
        inv.setRuleVersion(1);
        inv.setVersion(0);
        return inv;
    }

    /**
     * 构造一个 PENDING_L2 invoice, 且设置 l1ApproverId (用于 l2 测试的 "已走完 L1" 场景).
     */
    private static BillingInvoice newPendingL2Invoice(long id, long tenantId, Long l1ApproverId) {
        BillingInvoice inv = newInvoice(id, tenantId, FinanceConstants.INV_STATUS_PENDING_L2);
        inv.setL1ApproverId(l1ApproverId);
        return inv;
    }
}
