package com.lumen.wms.service;

import com.lumen.wms.WmsConstants;
import com.lumen.wms.approval.ThresholdEvaluator;
import com.lumen.wms.entity.WmsItem;
import com.lumen.wms.entity.WmsLocation;
import com.lumen.wms.entity.WmsOutboundLine;
import com.lumen.wms.entity.WmsOutboundNote;
import com.lumen.wms.exception.IllegalNoteStateException;
import com.lumen.wms.exception.OutboundNoteNotFoundException;
import com.lumen.wms.mapper.WmsItemMapper;
import com.lumen.wms.mapper.WmsLocationMapper;
import com.lumen.wms.mapper.WmsOutboundNoteMapper;
import com.lumen.wms.service.impl.OutboundServiceImpl;
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
 * OutboundService 状态机单元测试 (Iteration 3.0a PR4-T4.2).
 *
 * <p>纯 Mockito（无 Spring 上下文 / TestContainer），与 {@code InboundServiceStateMachineTest}
 * 同模式. 覆盖：
 * <ul>
 *   <li>{@code submit} — 阈值路由 / 空行 / 非 ACTIVE 库位 / 非 DRAFT 拒绝</li>
 *   <li>{@code approve} — 仅 PENDING_APPROVAL 可推进</li>
 *   <li>{@code pickLine} — 累加 pickedQty + 释放锁 / 全行拣完翻转为 REVIEW / 超量拒绝</li>
 *   <li>{@code ship} — 仅 REVIEW 可推进；写入 carrier / trackingNo</li>
 *   <li>{@code complete} — SHIPPED → DONE + flush 锁 + shippedAt</li>
 *   <li>{@code cancel} — 终态拒绝 / PICKING 释放未拣完锁</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboundService 状态机单元测试")
class OutboundServiceStateMachineTest {

    @Mock
    private WmsOutboundNoteMapper mapper;

    @Mock
    private ThresholdEvaluator evaluator;

    @Mock
    private WmsInventoryService inventoryService;

    @Mock
    private WmsItemMapper itemMapper;

    @Mock
    private WmsLocationMapper locationMapper;

    @InjectMocks
    private OutboundServiceImpl service;

    private WmsOutboundNote note;
    private WmsOutboundLine line;
    private WmsLocation activeLocation;

    @BeforeEach
    void setUp() {
        note = new WmsOutboundNote();
        note.setId(100L);
        note.setNo("OUT-001");
        note.setWarehouseId(10L);
        note.setStatus(WmsConstants.OUT_DRAFT);

        line = new WmsOutboundLine();
        line.setId(200L);
        line.setNoteId(100L);
        line.setLineNo(1);
        line.setItemId(999L);
        line.setQty(new BigDecimal("100"));
        line.setPickedQty(BigDecimal.ZERO);
        line.setSourceLocationId(555L);

        activeLocation = new WmsLocation();
        activeLocation.setId(555L);
        activeLocation.setStatus(WmsConstants.LOC_ACTIVE);

        // lenient: 并非每个用例都触达 selectById / selectLineById；
        // MockitoExtension 默认 STRICT_STUBS 会拒绝未消费的桩.
        lenient().when(mapper.selectById(100L)).thenReturn(note);
        lenient().when(mapper.selectLineById(200L)).thenReturn(line);
        lenient().when(locationMapper.selectById(555L)).thenReturn(activeLocation);
        lenient().when(itemMapper.selectById(999L)).thenReturn(itemWithCost(new BigDecimal("5")));
    }

    // ====================================================================
    // submit
    // ====================================================================

    @Test
    @DisplayName("submit: 阈值未超 → 路由到 PICKING + 锁库存")
    void submit_underThreshold_routesToPickingAndLocks() {
        when(mapper.selectLinesByNoteId(100L)).thenReturn(List.of(line));
        when(evaluator.requiresApproval(eq(WmsConstants.BIZ_OUTBOUND), any(), any()))
                .thenReturn(false);

        WmsOutboundNote result = service.submit(100L);

        assertThat(result.getStatus()).isEqualTo(WmsConstants.OUT_PICKING);
        verify(inventoryService, times(1))
                .lockQty(eq(10L), eq(555L), eq(999L), eq(new BigDecimal("100")));
        verify(mapper).updateById(any(WmsOutboundNote.class));
    }

    @Test
    @DisplayName("submit: 阈值超 → 路由到 PENDING_APPROVAL")
    void submit_overThreshold_routesToPendingApproval() {
        when(mapper.selectLinesByNoteId(100L)).thenReturn(List.of(line));
        when(itemMapper.selectById(999L)).thenReturn(itemWithCost(new BigDecimal("1000")));
        when(evaluator.requiresApproval(eq(WmsConstants.BIZ_OUTBOUND), any(), any()))
                .thenReturn(true);

        WmsOutboundNote result = service.submit(100L);

        assertThat(result.getStatus()).isEqualTo(WmsConstants.OUT_PENDING_APPROVAL);
    }

    @Test
    @DisplayName("submit: 无任何行 → 抛 IllegalNoteStateException")
    void submit_withNoLines_throwsIllegalState() {
        when(mapper.selectLinesByNoteId(100L)).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> service.submit(100L))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining("至少要有 1 条行");
        verify(inventoryService, never()).lockQty(any(), any(), any(), any());
        verify(mapper, never()).updateById(any(WmsOutboundNote.class));
    }

    @Test
    @DisplayName("submit: 源库位非 ACTIVE → 抛 IllegalNoteStateException")
    void submit_withInactiveLocation_throwsIllegalState() {
        WmsLocation frozen = new WmsLocation();
        frozen.setId(555L);
        frozen.setStatus(WmsConstants.LOC_FROZEN);
        when(mapper.selectLinesByNoteId(100L)).thenReturn(List.of(line));
        when(locationMapper.selectById(555L)).thenReturn(frozen);

        assertThatThrownBy(() -> service.submit(100L))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining("源库位不可用");
        verify(inventoryService, never()).lockQty(any(), any(), any(), any());
    }

    @Test
    @DisplayName("submit: 源库位缺失 → 抛 IllegalNoteStateException")
    void submit_withMissingLocation_throwsIllegalState() {
        line.setSourceLocationId(null);
        when(mapper.selectLinesByNoteId(100L)).thenReturn(List.of(line));

        assertThatThrownBy(() -> service.submit(100L))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining("sourceLocationId");
    }

    @Test
    @DisplayName("submit: 非 DRAFT 状态拒绝（已在 PICKING）")
    void submit_nonDraft_rejected() {
        note.setStatus(WmsConstants.OUT_PICKING);

        assertThatThrownBy(() -> service.submit(100L))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining(WmsConstants.OUT_DRAFT);
    }

    // ====================================================================
    // approve
    // ====================================================================

    @Test
    @DisplayName("approve: 仅 PENDING_APPROVAL 可推进到 PICKING")
    void approve_onlyPendingApproval_canTransition() {
        note.setStatus(WmsConstants.OUT_PENDING_APPROVAL);

        service.approve(100L);

        assertThat(note.getStatus()).isEqualTo(WmsConstants.OUT_PICKING);
        verify(mapper).updateById(note);
    }

    @Test
    @DisplayName("approve: PICKING 状态下调用被拒绝")
    void approve_nonPending_rejected() {
        note.setStatus(WmsConstants.OUT_PICKING);

        assertThatThrownBy(() -> service.approve(100L))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining(WmsConstants.OUT_PENDING_APPROVAL);
    }

    // ====================================================================
    // pickLine
    // ====================================================================

    @Test
    @DisplayName("pickLine: 累加 pickedQty, 释放锁库存, 未满不翻状态")
    void pickLine_inrementsPickedQty_releasesLockForPick() {
        note.setStatus(WmsConstants.OUT_PICKING);
        line.setQty(new BigDecimal("100"));
        line.setPickedQty(BigDecimal.ZERO);
        when(mapper.selectLinesByNoteId(100L)).thenReturn(List.of(line));

        WmsOutboundLine updated = service.pickLine(100L, 200L, new BigDecimal("40"));

        assertThat(updated.getPickedQty()).isEqualByComparingTo("40");
        assertThat(note.getStatus()).isEqualTo(WmsConstants.OUT_PICKING);
        verify(inventoryService, times(1))
                .releaseLock(eq(10L), eq(555L), eq(999L), eq(new BigDecimal("40")));
    }

    @Test
    @DisplayName("pickLine: 全行拣完 → 自动翻转为 REVIEW")
    void pickLine_fullyPicked_statusFlipsToReview() {
        note.setStatus(WmsConstants.OUT_PICKING);
        line.setQty(new BigDecimal("100"));
        line.setPickedQty(new BigDecimal("60"));
        when(mapper.selectLinesByNoteId(100L)).thenReturn(List.of(line));

        WmsOutboundLine updated = service.pickLine(100L, 200L, new BigDecimal("40"));

        assertThat(updated.getPickedQty()).isEqualByComparingTo("100");
        assertThat(note.getStatus()).isEqualTo(WmsConstants.OUT_REVIEW);
        verify(mapper, times(1)).updateById(note);
    }

    @Test
    @DisplayName("pickLine: 累加超过预期 qty → 抛 IllegalNoteStateException")
    void pickLine_exceedsLineQty_throws() {
        note.setStatus(WmsConstants.OUT_PICKING);
        line.setPickedQty(new BigDecimal("80"));

        assertThatThrownBy(() -> service.pickLine(100L, 200L, new BigDecimal("30")))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining("拣货数量超过预期");
        verify(inventoryService, never()).releaseLock(any(), any(), any(), any());
    }

    @Test
    @DisplayName("pickLine: 非 PICKING 状态拒绝")
    void pickLine_wrongState_rejected() {
        note.setStatus(WmsConstants.OUT_DRAFT);

        assertThatThrownBy(() -> service.pickLine(100L, 200L, new BigDecimal("10")))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining(WmsConstants.OUT_PICKING);
    }

    @Test
    @DisplayName("pickLine: qty ≤ 0 拒绝")
    void pickLine_nonPositiveQty_rejected() {
        note.setStatus(WmsConstants.OUT_PICKING);

        assertThatThrownBy(() -> service.pickLine(100L, 200L, BigDecimal.ZERO))
                .isInstanceOf(IllegalNoteStateException.class);
        assertThatThrownBy(() -> service.pickLine(100L, 200L, new BigDecimal("-1")))
                .isInstanceOf(IllegalNoteStateException.class);
    }

    // ====================================================================
    // ship
    // ====================================================================

    @Test
    @DisplayName("ship: 仅 REVIEW 可推进到 SHIPPED + 写入承运商 / 运单号")
    void ship_onlyReview_canTransition() {
        note.setStatus(WmsConstants.OUT_REVIEW);

        WmsOutboundNote result = service.ship(100L, "SF", "TRK-12345");

        assertThat(result.getStatus()).isEqualTo(WmsConstants.OUT_SHIPPED);
        assertThat(result.getCarrier()).isEqualTo("SF");
        assertThat(result.getTrackingNo()).isEqualTo("TRK-12345");
        verify(mapper).updateById(note);
    }

    @Test
    @DisplayName("ship: 非 REVIEW 状态拒绝")
    void ship_wrongState_rejected() {
        note.setStatus(WmsConstants.OUT_PICKING);

        assertThatThrownBy(() -> service.ship(100L, "SF", "TRK-12345"))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining(WmsConstants.OUT_REVIEW);
    }

    @Test
    @DisplayName("ship: carrier / trackingNo 为空 → 抛 IllegalNoteStateException")
    void ship_blankFields_rejected() {
        note.setStatus(WmsConstants.OUT_REVIEW);

        assertThatThrownBy(() -> service.ship(100L, "", "TRK-12345"))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining("承运商");
        assertThatThrownBy(() -> service.ship(100L, "SF", null))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining("运单号");
    }

    // ====================================================================
    // complete
    // ====================================================================

    @Test
    @DisplayName("complete: SHIPPED → DONE + flush 锁 + 设置 shippedAt")
    void complete_onlyShiptD_shipsToDone_flushesAllLocks() {
        note.setStatus(WmsConstants.OUT_SHIPPED);
        line.setPickedQty(new BigDecimal("100"));
        when(mapper.selectLinesByNoteId(100L)).thenReturn(List.of(line));

        WmsOutboundNote result = service.complete(100L);

        assertThat(result.getStatus()).isEqualTo(WmsConstants.OUT_DONE);
        assertThat(result.getShippedAt()).isNotNull();
        verify(inventoryService, times(1)).flushLock(eq(10L), eq(555L), eq(999L));
    }

    @Test
    @DisplayName("complete: 非 SHIPPED 状态拒绝")
    void complete_wrongState_rejected() {
        note.setStatus(WmsConstants.OUT_REVIEW);

        assertThatThrownBy(() -> service.complete(100L))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining(WmsConstants.OUT_SHIPPED);
        verify(inventoryService, never()).flushLock(any(), any(), any());
    }

    // ====================================================================
    // cancel
    // ====================================================================

    @Test
    @DisplayName("cancel: DONE 单据不可取消")
    void cancel_doneNote_throwsIllegalState() {
        note.setStatus(WmsConstants.OUT_DONE);

        assertThatThrownBy(() -> service.cancel(100L))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining("DONE");
        verify(mapper, never()).updateById(any(WmsOutboundNote.class));
    }

    @Test
    @DisplayName("cancel: 已 CANCELLED 不可重复取消")
    void cancel_alreadyCancelled_rejected() {
        note.setStatus(WmsConstants.OUT_CANCELLED);

        assertThatThrownBy(() -> service.cancel(100L))
                .isInstanceOf(IllegalNoteStateException.class);
    }

    @Test
    @DisplayName("cancel: DRAFT 可取消（无需释放锁）")
    void cancel_draft_succeeds_noLockRelease() {
        service.cancel(100L);
        assertThat(note.getStatus()).isEqualTo(WmsConstants.OUT_CANCELLED);
        verify(mapper).updateById(note);
        verify(inventoryService, never()).releaseLock(any(), any(), any(), any());
    }

    @Test
    @DisplayName("cancel: PICKING 状态释放未拣完的锁")
    void cancel_picking_releasesLocks() {
        note.setStatus(WmsConstants.OUT_PICKING);
        line.setQty(new BigDecimal("100"));
        line.setPickedQty(new BigDecimal("30"));
        when(mapper.selectLinesByNoteId(100L)).thenReturn(List.of(line));

        WmsOutboundNote result = service.cancel(100L);

        assertThat(result.getStatus()).isEqualTo(WmsConstants.OUT_CANCELLED);
        // 未拣完 = 100 - 30 = 70
        verify(inventoryService, times(1))
                .releaseLock(eq(10L), eq(555L), eq(999L), eq(new BigDecimal("70")));
    }

    @Test
    @DisplayName("cancel: PICKING 状态已全拣完 → 无需释放锁")
    void cancel_picking_fullyPicked_noLockRelease() {
        note.setStatus(WmsConstants.OUT_PICKING);
        line.setQty(new BigDecimal("100"));
        line.setPickedQty(new BigDecimal("100"));
        when(mapper.selectLinesByNoteId(100L)).thenReturn(List.of(line));

        service.cancel(100L);

        assertThat(note.getStatus()).isEqualTo(WmsConstants.OUT_CANCELLED);
        verify(inventoryService, never()).releaseLock(any(), any(), any(), any());
    }

    // ====================================================================
    // getById
    // ====================================================================

    @Test
    @DisplayName("getById: 不存在时抛 OutboundNoteNotFoundException")
    void getById_missing_throwsNotFound() {
        when(mapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.getById(404L))
                .isInstanceOf(OutboundNoteNotFoundException.class);
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
