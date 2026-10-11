package com.lumen.wms.service;

import com.lumen.wms.WmsConstants;
import com.lumen.wms.approval.ThresholdEvaluator;
import com.lumen.wms.entity.WmsInboundLine;
import com.lumen.wms.entity.WmsInboundNote;
import com.lumen.wms.entity.WmsItem;
import com.lumen.wms.exception.IllegalNoteStateException;
import com.lumen.wms.exception.InboundNoteNotFoundException;
import com.lumen.wms.mapper.WmsInboundNoteMapper;
import com.lumen.wms.mapper.WmsItemMapper;
import com.lumen.wms.service.impl.InboundServiceImpl;
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
 * InboundService 状态机单元测试 (Iteration 3.0a PR3-T3.4).
 *
 * <p>纯 Mockito（无 Spring 上下文 / TestContainer），与 {@code ThresholdEvaluatorTest}
 * 同模式. 覆盖：
 * <ul>
 *   <li>{@code submit} — 阈值路由 / 空行 / 非 DRAFT 拒绝</li>
 *   <li>{@code approve} — 仅 PENDING_APPROVAL 可推进</li>
 *   <li>{@code cancel} — 终态拒绝 / 其它状态允许</li>
 *   <li>{@code receiveLine} — 超过预期 / 全行收齐翻转为 PUTAWAY</li>
 *   <li>{@code putawayLine} — 超过已收 / 全行上架翻转为 DONE + completedAt</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InboundService 状态机单元测试")
class InboundServiceStateMachineTest {

    @Mock
    private WmsInboundNoteMapper mapper;

    @Mock
    private ThresholdEvaluator evaluator;

    @Mock
    private WmsInventoryService inventoryService;

    @Mock
    private WmsItemMapper itemMapper;

    @InjectMocks
    private InboundServiceImpl service;

    private WmsInboundNote note;
    private WmsInboundLine line;

    @BeforeEach
    void setUp() {
        note = new WmsInboundNote();
        note.setId(100L);
        note.setNo("INB-001");
        note.setWarehouseId(10L);
        note.setStatus(WmsConstants.INB_DRAFT);

        line = new WmsInboundLine();
        line.setId(200L);
        line.setNoteId(100L);
        line.setLineNo(1);
        line.setItemId(999L);
        line.setQty(new BigDecimal("100"));
        line.setReceivedQty(BigDecimal.ZERO);
        line.setPutawayQty(BigDecimal.ZERO);

        // lenient: 并非每个用例都触达 selectById / selectLineById；
        // MockitoExtension 默认 STRICT_STUBS 会拒绝未消费的桩.
        lenient().when(mapper.selectById(100L)).thenReturn(note);
        lenient().when(mapper.selectLineById(200L)).thenReturn(line);
    }

    // ====================================================================
    // submit
    // ====================================================================

    @Test
    @DisplayName("submit: 阈值未超 → 路由到 RECEIVING")
    void submit_underThreshold_routesToReceiving() {
        when(mapper.selectLinesByNoteId(100L)).thenReturn(List.of(line));
        when(itemMapper.selectById(999L)).thenReturn(itemWithCost(new BigDecimal("5")));
        when(evaluator.requiresApproval(eq(WmsConstants.BIZ_INBOUND), any(), any()))
                .thenReturn(false);

        WmsInboundNote result = service.submit(100L);

        assertThat(result.getStatus()).isEqualTo(WmsConstants.INB_RECEIVING);
        verify(mapper).updateById(any(WmsInboundNote.class));
    }

    @Test
    @DisplayName("submit: 阈值超 → 路由到 PENDING_APPROVAL")
    void submit_overThreshold_routesToPendingApproval() {
        when(mapper.selectLinesByNoteId(100L)).thenReturn(List.of(line));
        when(itemMapper.selectById(999L)).thenReturn(itemWithCost(new BigDecimal("1000")));
        when(evaluator.requiresApproval(eq(WmsConstants.BIZ_INBOUND), any(), any()))
                .thenReturn(true);

        WmsInboundNote result = service.submit(100L);

        assertThat(result.getStatus()).isEqualTo(WmsConstants.INB_PENDING_APPROVAL);
    }

    @Test
    @DisplayName("submit: 无任何行 → 抛 IllegalNoteStateException")
    void submit_withNoLines_throwsIllegalState() {
        when(mapper.selectLinesByNoteId(100L)).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> service.submit(100L))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining("至少要有 1 条行");
        verify(mapper, never()).updateById(any(WmsInboundNote.class));
    }

    @Test
    @DisplayName("submit: 非 DRAFT 状态拒绝（已在 RECEIVING）")
    void submit_nonDraft_rejected() {
        note.setStatus(WmsConstants.INB_RECEIVING);

        assertThatThrownBy(() -> service.submit(100L))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining(WmsConstants.INB_DRAFT);
    }

    // ====================================================================
    // approve
    // ====================================================================

    @Test
    @DisplayName("approve: 仅 PENDING_APPROVAL 可推进到 RECEIVING")
    void approve_onlyPendingApproval_canTransition() {
        note.setStatus(WmsConstants.INB_PENDING_APPROVAL);

        service.approve(100L);

        assertThat(note.getStatus()).isEqualTo(WmsConstants.INB_RECEIVING);
        verify(mapper).updateById(note);
    }

    @Test
    @DisplayName("approve: RECEIVING 状态下调用被拒绝")
    void approve_nonPending_rejected() {
        note.setStatus(WmsConstants.INB_RECEIVING);

        assertThatThrownBy(() -> service.approve(100L))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining(WmsConstants.INB_PENDING_APPROVAL);
    }

    // ====================================================================
    // cancel
    // ====================================================================

    @Test
    @DisplayName("cancel: DRAFT 可取消")
    void cancel_draftNote_succeeds() {
        service.cancel(100L);
        assertThat(note.getStatus()).isEqualTo(WmsConstants.INB_CANCELLED);
        verify(mapper).updateById(note);
    }

    @Test
    @DisplayName("cancel: RECEIVING 可取消")
    void cancel_receiving_succeeds() {
        note.setStatus(WmsConstants.INB_RECEIVING);

        service.cancel(100L);
        assertThat(note.getStatus()).isEqualTo(WmsConstants.INB_CANCELLED);
    }

    @Test
    @DisplayName("cancel: DONE 单据不可取消")
    void cancel_doneNote_throwsIllegalState() {
        note.setStatus(WmsConstants.INB_DONE);

        assertThatThrownBy(() -> service.cancel(100L))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining("DONE");
        verify(mapper, never()).updateById(any(WmsInboundNote.class));
    }

    @Test
    @DisplayName("cancel: 已 CANCELLED 不可重复取消")
    void cancel_alreadyCancelled_rejected() {
        note.setStatus(WmsConstants.INB_CANCELLED);

        assertThatThrownBy(() -> service.cancel(100L))
                .isInstanceOf(IllegalNoteStateException.class);
    }

    // ====================================================================
    // receiveLine
    // ====================================================================

    @Test
    @DisplayName("receiveLine: 累加 receivedQty, 未满不翻状态")
    void receiveLine_partial_keepsStatusReceiving() {
        note.setStatus(WmsConstants.INB_RECEIVING);
        line.setQty(new BigDecimal("100"));
        line.setReceivedQty(BigDecimal.ZERO);
        when(mapper.selectLinesByNoteId(100L)).thenReturn(List.of(line));

        WmsInboundLine updated = service.receiveLine(100L, 200L, new BigDecimal("40"));

        assertThat(updated.getReceivedQty()).isEqualByComparingTo("40");
        assertThat(note.getStatus()).isEqualTo(WmsConstants.INB_RECEIVING);
        verify(inventoryService, never()).addQty(any(), any(), any(), any());
    }

    @Test
    @DisplayName("receiveLine: 全行收齐 → 自动翻转为 PUTAWAY")
    void receiveLine_fullyReceived_statusFlipsToPutaway() {
        note.setStatus(WmsConstants.INB_RECEIVING);
        line.setQty(new BigDecimal("100"));
        line.setReceivedQty(new BigDecimal("60"));
        when(mapper.selectLinesByNoteId(100L)).thenReturn(List.of(line));

        WmsInboundLine updated = service.receiveLine(100L, 200L, new BigDecimal("40"));

        assertThat(updated.getReceivedQty()).isEqualByComparingTo("100");
        assertThat(note.getStatus()).isEqualTo(WmsConstants.INB_PUTAWAY);
        verify(mapper, times(1)).updateLineById(line);
    }

    @Test
    @DisplayName("receiveLine: 累加超过预期 qty → 抛 IllegalNoteStateException")
    void receiveLine_exceedsQty_throws() {
        note.setStatus(WmsConstants.INB_RECEIVING);
        line.setReceivedQty(new BigDecimal("80"));

        assertThatThrownBy(() -> service.receiveLine(100L, 200L, new BigDecimal("30")))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining("收货数量超过预期");
        verify(mapper, never()).updateLineById(any());
    }

    @Test
    @DisplayName("receiveLine: 非 RECEIVING 状态拒绝")
    void receiveLine_wrongState_rejected() {
        note.setStatus(WmsConstants.INB_DRAFT);

        assertThatThrownBy(() -> service.receiveLine(100L, 200L, new BigDecimal("10")))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining(WmsConstants.INB_RECEIVING);
    }

    @Test
    @DisplayName("receiveLine: qty ≤ 0 拒绝")
    void receiveLine_nonPositiveQty_rejected() {
        note.setStatus(WmsConstants.INB_RECEIVING);

        assertThatThrownBy(() -> service.receiveLine(100L, 200L, BigDecimal.ZERO))
                .isInstanceOf(IllegalNoteStateException.class);
        assertThatThrownBy(() -> service.receiveLine(100L, 200L, new BigDecimal("-1")))
                .isInstanceOf(IllegalNoteStateException.class);
    }

    // ====================================================================
    // putawayLine
    // ====================================================================

    @Test
    @DisplayName("putawayLine: 累加 putawayQty, 触发 inventoryService.addQty")
    void putawayLine_partial_invokesAddQty() {
        note.setStatus(WmsConstants.INB_PUTAWAY);
        line.setReceivedQty(new BigDecimal("100"));
        line.setPutawayQty(BigDecimal.ZERO);
        line.setPutawayLocationId(null);
        when(mapper.selectLinesByNoteId(100L)).thenReturn(List.of(line));

        WmsInboundLine updated = service.putawayLine(
                100L, 200L, new BigDecimal("40"), 555L);

        assertThat(updated.getPutawayQty()).isEqualByComparingTo("40");
        assertThat(line.getPutawayLocationId()).isEqualTo(555L);
        assertThat(note.getStatus()).isEqualTo(WmsConstants.INB_PUTAWAY);
        verify(inventoryService, times(1))
                .addQty(eq(10L), eq(555L), eq(999L), eq(new BigDecimal("40")));
    }

    @Test
    @DisplayName("putawayLine: 全行上架完 → 翻转为 DONE + 设置 completedAt")
    void putawayLine_fullyPutawayed_statusFlipsToDone_setsCompletedAt() {
        note.setStatus(WmsConstants.INB_PUTAWAY);
        line.setReceivedQty(new BigDecimal("100"));
        line.setPutawayQty(new BigDecimal("60"));
        line.setPutawayLocationId(555L);
        when(mapper.selectLinesByNoteId(100L)).thenReturn(List.of(line));

        WmsInboundLine updated = service.putawayLine(
                100L, 200L, new BigDecimal("40"), 555L);

        assertThat(updated.getPutawayQty()).isEqualByComparingTo("100");
        assertThat(note.getStatus()).isEqualTo(WmsConstants.INB_DONE);
        assertThat(note.getCompletedAt()).isNotNull();
        verify(mapper, times(1)).updateById(note);
    }

    @Test
    @DisplayName("putawayLine: 累加超过已收货 → 抛 IllegalNoteStateException")
    void putawayLine_exceedsReceived_throws() {
        note.setStatus(WmsConstants.INB_PUTAWAY);
        line.setReceivedQty(new BigDecimal("100"));
        line.setPutawayQty(new BigDecimal("80"));

        assertThatThrownBy(() ->
                service.putawayLine(100L, 200L, new BigDecimal("30"), 555L))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining("上架数量超过已收货");
        verify(inventoryService, never()).addQty(any(), any(), any(), any());
    }

    @Test
    @DisplayName("putawayLine: 非 PUTAWAY 状态拒绝")
    void putawayLine_wrongState_rejected() {
        note.setStatus(WmsConstants.INB_RECEIVING);

        assertThatThrownBy(() ->
                service.putawayLine(100L, 200L, new BigDecimal("10"), 555L))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining(WmsConstants.INB_PUTAWAY);
    }

    @Test
    @DisplayName("putawayLine: 未指定库位且行无库位 → 抛 IllegalNoteStateException")
    void putawayLine_missingLocation_rejected() {
        note.setStatus(WmsConstants.INB_PUTAWAY);
        line.setReceivedQty(new BigDecimal("100"));
        line.setPutawayLocationId(null);

        assertThatThrownBy(() ->
                service.putawayLine(100L, 200L, new BigDecimal("10"), null))
                .isInstanceOf(IllegalNoteStateException.class)
                .hasMessageContaining("未指定库位");
    }

    // ====================================================================
    // getById — 转发到 mapper.selectById + 不存在时抛 InboundNoteNotFoundException
    // ====================================================================

    @Test
    @DisplayName("getById: 不存在时抛 InboundNoteNotFoundException")
    void getById_missing_throwsNotFound() {
        when(mapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.getById(404L))
                .isInstanceOf(InboundNoteNotFoundException.class);
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