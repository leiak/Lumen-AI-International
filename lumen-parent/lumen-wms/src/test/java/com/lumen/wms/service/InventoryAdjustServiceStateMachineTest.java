package com.lumen.wms.service;

import com.lumen.wms.WmsConstants;
import com.lumen.wms.approval.ThresholdEvaluator;
import com.lumen.wms.entity.WmsInventoryAdjust;
import com.lumen.wms.entity.WmsInventoryAdjustLine;
import com.lumen.wms.entity.WmsItem;
import com.lumen.wms.exception.IllegalNoteStateException;
import com.lumen.wms.mapper.WmsInventoryAdjustMapper;
import com.lumen.wms.mapper.WmsItemMapper;
import com.lumen.wms.service.impl.InventoryAdjustServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * InventoryAdjustService 状态机单元测试 (Iteration 3.0a PR5-T5.1).
 *
 * <p>纯 Mockito（无 Spring 上下文 / TestContainer），与 {@code InboundServiceStateMachineTest}
 * 同模式. 覆盖：
 * <ul>
 *   <li>{@code submit} — 阈值路由 / 空行 / 非 DRAFT 拒绝 / 行 deltaQty=0 拒绝</li>
 *   <li>{@code approve} — 仅 PENDING_APPROVAL 可推进到 APPROVED</li>
 *   <li>{@code reject} — 仅 PENDING_APPROVAL 可推进到 REJECTED</li>
 *   <li>{@code execute} — APPROVED → DONE + 对每行调用 {@code adjustDelta}；非 APPROVED 拒绝</li>
 *   <li>{@code cancel} — DRAFT/PENDING_APPROVAL/APPROVED 可取消；DONE/REJECTED/CANCELLED 拒绝</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InventoryAdjustService 状态机单元测试")
class InventoryAdjustServiceStateMachineTest {

    @Mock
    private WmsInventoryAdjustMapper mapper;

    @Mock
    private ThresholdEvaluator evaluator;

    @Mock
    private WmsInventoryService inventoryService;

    @Mock
    private WmsItemMapper itemMapper;

    @InjectMocks
    private InventoryAdjustServiceImpl service;

    private WmsInventoryAdjust note;
    private WmsInventoryAdjustLine line;

    @BeforeEach
    void setUp() {
        note = new WmsInventoryAdjust();
        note.setId(100L);
        note.setNo("ADJ-001");
        note.setWarehouseId(10L);
        note.setStatus(WmsConstants.ADJ_DRAFT);

        line = new WmsInventoryAdjustLine();
        line.setId(200L);
        line.setAdjustId(100L);
        line.setLineNo(1);
        line.setItemId(999L);
        line.setLocationId(555L);
        line.setDeltaQty(new BigDecimal("10"));

        // lenient: 并非每个用例都触达 selectById / selectLineById；
        // MockitoExtension 默认 STRICT_STUBS 会拒绝未消费的桩.
        lenient().when(mapper.selectById(100L)).thenReturn(note);
        lenient().when(mapper.selectLineById(200L)).thenReturn(line);
    }

    // ====================================================================
    // submit
    // ====================================================================

    @Test
    @DisplayName("submit: 阈值未超 → 路由到 APPROVED")
    void submit_belowThreshold_goesToApproved() {
        when(mapper.selectLinesByAdjustId(100L)).thenReturn(List.of(line));
        when(itemMapper.selectById(999L)).thenReturn(itemWithCost(new BigDecimal("5")));
        when(evaluator.requiresApproval(eq(WmsConstants.BIZ_ADJUST), any(), any()))
                .thenReturn(false);

        WmsInventoryAdjust result = service.submit(100L);

        assertThat(result.getStatus()).isEqualTo(WmsConstants.ADJ_APPROVED);
        verify(mapper).updateById(any(WmsInventoryAdjust.class));
    }

    @Test
    @DisplayName("submit: 阈值超 → 路由到 PENDING_APPROVAL")
    void submit_aboveThreshold_goesToPendingApproval() {
        when(mapper.selectLinesByAdjustId(100L)).thenReturn(List.of(line));
        when(itemMapper.selectById(999L)).thenReturn(itemWithCost(new BigDecimal("100000")));
        when(evaluator.requiresApproval(eq(WmsConstants.BIZ_ADJUST), any(), any()))
                .thenReturn(true);

        WmsInventoryAdjust result = service.submit(100L);

        assertThat(result.getStatus()).isEqualTo(WmsConstants.ADJ_PENDING_APPROVAL);
        verify(mapper).updateById(any(WmsInventoryAdjust.class));
    }

    @Test
    @DisplayName("submit: 无任何行 → 抛 IllegalNoteStateException")
    void submit_withNoLines_throwsIllegalState() {
        when(mapper.selectLinesByAdjustId(100L)).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> service.submit(100L))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining("至少要有 1 条行");
        verify(mapper, never()).updateById(any(WmsInventoryAdjust.class));
    }

    @Test
    @DisplayName("submit: 非 DRAFT 状态拒绝（已在 APPROVED）")
    void submit_fromNonDraft_throwsIllegalState() {
        note.setStatus(WmsConstants.ADJ_APPROVED);

        assertThatThrownBy(() -> service.submit(100L))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining(WmsConstants.ADJ_DRAFT);
        verify(evaluator, never()).requiresApproval(any(), any(), any());
    }

    @Test
    @DisplayName("submit: 行 deltaQty=0 → 抛 IllegalNoteStateException")
    void submit_lineWithZeroDelta_throwsIllegalState() {
        line.setDeltaQty(BigDecimal.ZERO);
        when(mapper.selectLinesByAdjustId(100L)).thenReturn(List.of(line));

        assertThatThrownBy(() -> service.submit(100L))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining("deltaQty");
        verify(evaluator, never()).requiresApproval(any(), any(), any());
    }

    // ====================================================================
    // approve
    // ====================================================================

    @Test
    @DisplayName("approve: PENDING_APPROVAL → APPROVED")
    void approve_pendingApprovalToApproved() {
        note.setStatus(WmsConstants.ADJ_PENDING_APPROVAL);

        WmsInventoryAdjust result = service.approve(100L);

        assertThat(result.getStatus()).isEqualTo(WmsConstants.ADJ_APPROVED);
        verify(mapper).updateById(note);
    }

    @Test
    @DisplayName("approve: 非 PENDING_APPROVAL 状态拒绝（DRAFT）")
    void approve_nonPending_rejected() {
        note.setStatus(WmsConstants.ADJ_DRAFT);

        assertThatThrownBy(() -> service.approve(100L))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining(WmsConstants.ADJ_PENDING_APPROVAL);
        verify(mapper, never()).updateById(any(WmsInventoryAdjust.class));
    }

    // ====================================================================
    // reject
    // ====================================================================

    @Test
    @DisplayName("reject: PENDING_APPROVAL → REJECTED,reason 写入 rejectReason")
    void reject_pendingApprovalToRejected() {
        note.setStatus(WmsConstants.ADJ_PENDING_APPROVAL);

        WmsInventoryAdjust result = service.reject(100L, "测试驳回原因");

        assertThat(result.getStatus()).isEqualTo(WmsConstants.ADJ_REJECTED);
        assertThat(result.getRejectReason()).isEqualTo("测试驳回原因");
        verify(mapper).updateById(note);
    }

    @Test
    @DisplayName("reject: 非 PENDING_APPROVAL 状态拒绝（APPROVED）")
    void reject_nonPending_rejected() {
        note.setStatus(WmsConstants.ADJ_APPROVED);

        assertThatThrownBy(() -> service.reject(100L, "no"))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining(WmsConstants.ADJ_PENDING_APPROVAL);
    }

    // ====================================================================
    // execute
    // ====================================================================

    @Test
    @DisplayName("execute: APPROVED → DONE，正负 delta 都调用 adjustDelta")
    void execute_approvedToDone_appliesDeltas() {
        note.setStatus(WmsConstants.ADJ_APPROVED);

        WmsInventoryAdjustLine positive = new WmsInventoryAdjustLine();
        positive.setId(200L);
        positive.setAdjustId(100L);
        positive.setItemId(999L);
        positive.setLocationId(555L);
        positive.setDeltaQty(new BigDecimal("15"));

        WmsInventoryAdjustLine negative = new WmsInventoryAdjustLine();
        negative.setId(201L);
        negative.setAdjustId(100L);
        negative.setItemId(888L);
        negative.setLocationId(556L);
        negative.setDeltaQty(new BigDecimal("-7"));

        when(mapper.selectLinesByAdjustId(100L)).thenReturn(List.of(positive, negative));

        WmsInventoryAdjust result = service.execute(100L);

        assertThat(result.getStatus()).isEqualTo(WmsConstants.ADJ_DONE);
        verify(inventoryService, times(1))
                .adjustDelta(eq(10L), eq(555L), eq(999L), eq(new BigDecimal("15")));
        verify(inventoryService, times(1))
                .adjustDelta(eq(10L), eq(556L), eq(888L), eq(new BigDecimal("-7")));
        verify(mapper).updateById(note);
    }

    @Test
    @DisplayName("execute: 非 APPROVED 状态拒绝（DRAFT）")
    void execute_fromNonApproved_throwsIllegalState() {
        note.setStatus(WmsConstants.ADJ_DRAFT);

        assertThatThrownBy(() -> service.execute(100L))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining(WmsConstants.ADJ_APPROVED);
        verify(inventoryService, never()).adjustDelta(any(), any(), any(), any());
        verify(mapper, never()).updateById(any(WmsInventoryAdjust.class));
    }

    @Test
    @DisplayName("execute: 行 deltaQty=0 → 抛 IllegalNoteStateException 且不入账本")
    void execute_lineWithZeroDelta_throwsIllegalState() {
        note.setStatus(WmsConstants.ADJ_APPROVED);
        line.setDeltaQty(BigDecimal.ZERO);
        when(mapper.selectLinesByAdjustId(100L)).thenReturn(List.of(line));

        assertThatThrownBy(() -> service.execute(100L))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining("deltaQty");
        verify(inventoryService, never()).adjustDelta(any(), any(), any(), any());
    }

    // ====================================================================
    // cancel
    // ====================================================================

    @Test
    @DisplayName("cancel: DRAFT → CANCELLED")
    void cancel_fromDRAFT_RevToCancelled() {
        WmsInventoryAdjust result = service.cancel(100L);

        assertThat(result.getStatus()).isEqualTo(WmsConstants.ADJ_CANCELLED);
        verify(mapper).updateById(note);
    }

    @Test
    @DisplayName("cancel: APPROVED → CANCELLED（execute 未调用则无需还原库存）")
    void cancel_fromApproved_succeeds() {
        note.setStatus(WmsConstants.ADJ_APPROVED);

        WmsInventoryAdjust result = service.cancel(100L);

        assertThat(result.getStatus()).isEqualTo(WmsConstants.ADJ_CANCELLED);
        verify(inventoryService, never()).adjustDelta(any(), any(), any(), any());
        verify(mapper).updateById(note);
    }

    @Test
    @DisplayName("cancel: PENDING_APPROVAL → CANCELLED（审批前撤销，无需还原库存）")
    void cancel_fromPendingApproval_succeeds() {
        note.setStatus(WmsConstants.ADJ_PENDING_APPROVAL);

        WmsInventoryAdjust result = service.cancel(100L);

        assertThat(result.getStatus()).isEqualTo(WmsConstants.ADJ_CANCELLED);
        verify(inventoryService, never()).adjustDelta(any(), any(), any(), any());
        verify(mapper).updateById(note);
    }

    @Test
    @DisplayName("cancel: DONE 单据不可取消")
    void cancel_doneNote_throwsIllegalState() {
        note.setStatus(WmsConstants.ADJ_DONE);

        assertThatThrownBy(() -> service.cancel(100L))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining("DONE");
        verify(mapper, never()).updateById(any(WmsInventoryAdjust.class));
    }

    @Test
    @DisplayName("cancel: REJECTED 单据不可取消")
    void cancel_rejectedNote_throwsIllegalState() {
        note.setStatus(WmsConstants.ADJ_REJECTED);

        assertThatThrownBy(() -> service.cancel(100L))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining("REJECTED");
        verify(mapper, never()).updateById(any(WmsInventoryAdjust.class));
    }

    @Test
    @DisplayName("cancel: 已 CANCELLED 不可重复取消")
    void cancel_alreadyCancelled_rejected() {
        note.setStatus(WmsConstants.ADJ_CANCELLED);

        assertThatThrownBy(() -> service.cancel(100L))
                .isInstanceOf(IllegalNoteStateException.class);
        verify(mapper, never()).updateById(any(WmsInventoryAdjust.class));
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private WmsItem itemWithCost(BigDecimal cost) {
        WmsItem item = new WmsItem();
        item.setId(999L);
        item.setSku("SKU-001");
        item.setName("test item");
        item.setUnitCost(cost);
        return item;
    }
}