package com.lumen.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.wms.WmsConstants;
import com.lumen.wms.approval.ThresholdEvaluator;
import com.lumen.wms.dto.AddInboundLineRequest;
import com.lumen.wms.dto.CreateInboundNoteRequest;
import com.lumen.wms.dto.InboundLineDto;
import com.lumen.wms.dto.InboundLineVO;
import com.lumen.wms.dto.InboundNoteVO;
import com.lumen.wms.dto.UpdateInboundNoteRequest;
import com.lumen.wms.entity.WmsInboundLine;
import com.lumen.wms.entity.WmsInboundNote;
import com.lumen.wms.entity.WmsItem;
import com.lumen.wms.exception.IllegalNoteStateException;
import com.lumen.wms.exception.InboundNoteNotFoundException;
import com.lumen.wms.exception.WmsDuplicateCodeException;
import com.lumen.wms.mapper.WmsInboundNoteMapper;
import com.lumen.wms.mapper.WmsItemMapper;
import com.lumen.wms.service.InboundService;
import com.lumen.wms.service.WmsInventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 入库单主表 / 行表持久化服务实现.
 *
 * <p>T3.1：基础 CRUD（create / update / delete / addLine / removeLine / page）。
 * T3.4：状态机 — {@code submit / approve / cancel / receiveLine / putawayLine}.
 * 收货 / 上架自动驱动状态翻转（RECEIVING → PUTAWAY → DONE），并由
 * {@link ThresholdEvaluator} 在 {@code submit} 时按阈值路由.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboundServiceImpl implements InboundService {

    private final WmsInboundNoteMapper mapper;
    private final ThresholdEvaluator evaluator;
    private final WmsInventoryService inventoryService;
    private final WmsItemMapper itemMapper;

    @Override
    public PageResult<WmsInboundNote> page(long pageNum, long pageSize, String keyword, String status) {
        Page<WmsInboundNote> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<WmsInboundNote> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.like(WmsInboundNote::getNo, keyword);
        }
        if (status != null && !status.isBlank()) {
            qw.eq(WmsInboundNote::getStatus, status);
        }
        qw.orderByDesc(WmsInboundNote::getId);
        Page<WmsInboundNote> res = mapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), pageNum, pageSize);
    }

    @Override
    public WmsInboundNote getById(Long id) {
        WmsInboundNote note = mapper.selectById(id);
        if (note == null) throw new InboundNoteNotFoundException(id);
        return note;
    }

    @Override
    @Transactional
    public WmsInboundNote create(CreateInboundNoteRequest req) {
        WmsInboundNote note = new WmsInboundNote();
        BeanUtils.copyProperties(req, note);
        if (note.getTenantId() == null) {
            note.setTenantId(TenantContext.require());
        }
        if (note.getStatus() == null) {
            note.setStatus("DRAFT");
        }
        try {
            mapper.insert(note);
        } catch (DuplicateKeyException e) {
            log.warn("duplicate inbound no: {}", req.getNo());
            throw new WmsDuplicateCodeException("inbound", req.getNo());
        }
        insertLines(note.getId(), req.getLines());
        return mapper.selectById(note.getId());
    }

    @Override
    @Transactional
    public WmsInboundNote update(Long id, UpdateInboundNoteRequest req) {
        WmsInboundNote existing = getById(id);
        BeanUtils.copyProperties(req, existing, "id", "tenantId", "createdAt");
        if (existing.getStatus() == null) {
            // 不允许通过 update 把 status 写空
            existing.setStatus("DRAFT");
        }
        try {
            mapper.updateById(existing);
        } catch (DuplicateKeyException e) {
            log.warn("duplicate inbound no on update: {}", req.getNo());
            throw new WmsDuplicateCodeException("inbound", req.getNo());
        }
        if (req.hasLines()) {
            // 整量替换：先删旧再插新
            mapper.deleteLinesByNoteId(id);
            if (req.hasNonEmptyLines()) {
                insertLines(id, req.getLines());
            }
        }
        return mapper.selectById(id);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        // getById 会抛 InboundNoteNotFoundException
        getById(id);
        mapper.deleteById(id);
        mapper.deleteLinesByNoteId(id);
    }

    @Override
    @Transactional
    public WmsInboundLine addLine(Long noteId, AddInboundLineRequest req) {
        // 触发父级存在性校验
        getById(noteId);

        WmsInboundLine line = new WmsInboundLine();
        BeanUtils.copyProperties(req, line, "lineNo");
        line.setNoteId(noteId);

        if (req.getLineNo() != null) {
            line.setLineNo(req.getLineNo());
            // lineNo 冲突时让 DB 抛 DuplicateKey
        } else {
            line.setLineNo(nextLineNo(noteId));
        }
        if (line.getReceivedQty() == null) {
            line.setReceivedQty(java.math.BigDecimal.ZERO);
        }
        if (line.getPutawayQty() == null) {
            line.setPutawayQty(java.math.BigDecimal.ZERO);
        }
        if (line.getTenantId() == null) {
            line.setTenantId(TenantContext.require());
        }
        mapper.insertLine(line);
        return line;
    }

    @Override
    @Transactional
    public void removeLine(Long noteId, Long lineId) {
        // 触发父级存在性校验
        getById(noteId);
        WmsInboundLine existing = mapper.selectLineById(lineId);
        if (existing == null || !existing.getNoteId().equals(noteId)) {
            throw new InboundNoteNotFoundException(noteId);
        }
        mapper.deleteLineById(lineId);
    }

    @Override
    public List<WmsInboundLine> listLines(Long noteId) {
        // 触发父级存在性校验
        getById(noteId);
        return mapper.selectLinesByNoteId(noteId);
    }

    @Override
    public InboundNoteVO toVO(WmsInboundNote note) {
        if (note == null) return null;
        InboundNoteVO vo = new InboundNoteVO();
        BeanUtils.copyProperties(note, vo);
        List<WmsInboundLine> lines = mapper.selectLinesByNoteId(note.getId());
        vo.setLines(lineVOs(lines));
        return vo;
    }

    @Override
    public List<InboundLineVO> lineVOs(List<WmsInboundLine> lines) {
        List<InboundLineVO> result = new ArrayList<>();
        if (lines == null) return result;
        for (WmsInboundLine line : lines) {
            InboundLineVO lvo = new InboundLineVO();
            BeanUtils.copyProperties(line, lvo);
            result.add(lvo);
        }
        return result;
    }

    // ====================================================================
    // T3.4 状态机 — submit / approve / cancel / receiveLine / putawayLine
    // ====================================================================

    @Override
    @Transactional
    public WmsInboundNote submit(Long id) {
        WmsInboundNote note = mustInStatus(id, WmsConstants.INB_DRAFT);
        List<WmsInboundLine> lines = mapper.selectLinesByNoteId(id);
        if (lines.isEmpty()) {
            throw new IllegalNoteStateException("入库单至少要有 1 条行: id=" + id);
        }

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (WmsInboundLine line : lines) {
            if (line.getQty() == null) {
                throw new IllegalNoteStateException(
                        "入库单行 qty 为空: id=" + id + " line=" + line.getId());
            }
            totalQty = totalQty.add(line.getQty());

            WmsItem item = itemMapper.selectById(line.getItemId());
            if (item == null) {
                throw new IllegalNoteStateException(
                        "找不到货品: line=" + line.getId() + " itemId=" + line.getItemId());
            }
            if (item.getUnitCost() != null) {
                totalAmount = totalAmount.add(item.getUnitCost().multiply(line.getQty()));
            }
        }

        boolean needApproval = evaluator.requiresApproval(
                WmsConstants.BIZ_INBOUND, totalAmount, totalQty);
        String target = needApproval
                ? WmsConstants.INB_PENDING_APPROVAL
                : WmsConstants.INB_RECEIVING;

        note.setStatus(target);
        mapper.updateById(note);

        // TODO(PR-wms-approval-record): 创建审批记录 — 待 ApprovalRouter.chains
        //      配置 WMS_INBOUND 后，调用 ApprovalService.submit() 生成 approval_record.
        //      当前仅设置单据状态为 PENDING_APPROVAL，业务侧可在状态为 PENDING_APPROVAL 时
        //      通过手动审批按钮或后续 PR 接入 ApprovalService.submit() 来驱动审批流.
        log.info("Inbound note {} submitted: status={} qty={} amount={}",
                id, target, totalQty, totalAmount);
        return mapper.selectById(id);
    }

    @Override
    @Transactional
    public void approve(Long id) {
        WmsInboundNote note = mustInStatus(id, WmsConstants.INB_PENDING_APPROVAL);
        note.setStatus(WmsConstants.INB_RECEIVING);
        mapper.updateById(note);
        log.info("Inbound note {} approved -> RECEIVING", id);
    }

    @Override
    @Transactional
    public void cancel(Long id) {
        WmsInboundNote note = getById(id);
        String s = note.getStatus();
        if (WmsConstants.INB_DONE.equals(s) || WmsConstants.INB_CANCELLED.equals(s)) {
            throw new IllegalNoteStateException(
                    "DONE/CANCELLED 单据不可取消: id=" + id + " status=" + s);
        }
        note.setStatus(WmsConstants.INB_CANCELLED);
        mapper.updateById(note);
        log.info("Inbound note {} cancelled: prevStatus={}", id, s);
    }

    @Override
    @Transactional
    public WmsInboundLine receiveLine(Long noteId, Long lineId, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            throw new IllegalNoteStateException("收货数量必须 > 0: line=" + lineId);
        }
        WmsInboundNote note = mustInStatus(noteId, WmsConstants.INB_RECEIVING);
        WmsInboundLine line = mustLineInNote(noteId, lineId);

        BigDecimal cur = line.getReceivedQty() == null ? BigDecimal.ZERO : line.getReceivedQty();
        BigDecimal next = cur.add(qty);
        if (next.compareTo(line.getQty()) > 0) {
            throw new IllegalNoteStateException(
                    "收货数量超过预期: line=" + lineId
                            + " expected=" + line.getQty()
                            + " received=" + next);
        }
        line.setReceivedQty(next);
        mapper.updateLineById(line);

        // 全行收齐 → PUTAWAY
        List<WmsInboundLine> allLines = mapper.selectLinesByNoteId(noteId);
        boolean allReceived = allLines.stream().allMatch(l ->
                l.getReceivedQty() != null && l.getReceivedQty().compareTo(l.getQty()) >= 0);
        if (allReceived) {
            note.setStatus(WmsConstants.INB_PUTAWAY);
            mapper.updateById(note);
            log.info("Inbound note {} -> PUTAWAY (all lines received)", noteId);
        }
        return mapper.selectLineById(lineId);
    }

    @Override
    @Transactional
    public WmsInboundLine putawayLine(Long noteId, Long lineId, BigDecimal qty, Long locationId) {
        if (qty == null || qty.signum() <= 0) {
            throw new IllegalNoteStateException("上架数量必须 > 0: line=" + lineId);
        }
        WmsInboundNote note = mustInStatus(noteId, WmsConstants.INB_PUTAWAY);
        WmsInboundLine line = mustLineInNote(noteId, lineId);

        BigDecimal curPut = line.getPutawayQty() == null ? BigDecimal.ZERO : line.getPutawayQty();
        BigDecimal nextPut = curPut.add(qty);
        if (nextPut.compareTo(line.getReceivedQty()) > 0) {
            throw new IllegalNoteStateException(
                    "上架数量超过已收货: line=" + lineId
                            + " received=" + line.getReceivedQty()
                            + " putaway=" + nextPut);
        }

        // 首次上架锁定库位
        if (line.getPutawayLocationId() == null && locationId == null) {
            throw new IllegalNoteStateException("上架行未指定库位: line=" + lineId);
        }
        if (line.getPutawayLocationId() == null) {
            line.setPutawayLocationId(locationId);
        }
        line.setPutawayQty(nextPut);
        mapper.updateLineById(line);

        // 增加库存余额
        Long locId = line.getPutawayLocationId();
        inventoryService.addQty(note.getWarehouseId(), locId, line.getItemId(), qty);

        // 全行上架完成 → DONE
        List<WmsInboundLine> allLines = mapper.selectLinesByNoteId(noteId);
        boolean allPutawayed = allLines.stream().allMatch(l ->
                l.getPutawayQty() != null && l.getPutawayQty().compareTo(l.getReceivedQty()) >= 0);
        if (allPutawayed) {
            note.setStatus(WmsConstants.INB_DONE);
            note.setCompletedAt(LocalDateTime.now());
            mapper.updateById(note);
            log.info("Inbound note {} -> DONE (all lines putawayed)", noteId);
        }
        return mapper.selectLineById(lineId);
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private void insertLines(Long noteId, List<InboundLineDto> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return;
        }
        Long tenantId = TenantContext.require();
        int autoLineNo = 1;
        for (InboundLineDto dto : dtos) {
            WmsInboundLine line = new WmsInboundLine();
            line.setTenantId(tenantId);
            line.setNoteId(noteId);
            line.setLineNo(autoLineNo++);
            line.setItemId(dto.getItemId());
            line.setQty(dto.getQty());
            line.setReceivedQty(java.math.BigDecimal.ZERO);
            line.setPutawayLocationId(dto.getPutawayLocationId());
            line.setPutawayQty(java.math.BigDecimal.ZERO);
            mapper.insertLine(line);
        }
    }

    private int nextLineNo(Long noteId) {
        List<WmsInboundLine> existing = mapper.selectLinesByNoteId(noteId);
        int max = 0;
        for (WmsInboundLine l : existing) {
            if (l.getLineNo() != null && l.getLineNo() > max) {
                max = l.getLineNo();
            }
        }
        return max + 1;
    }

    /**
     * 校验单据状态 — 不符则抛 {@link IllegalNoteStateException}.
     */
    private WmsInboundNote mustInStatus(Long id, String expected) {
        WmsInboundNote note = getById(id);
        if (!expected.equals(note.getStatus())) {
            throw new IllegalNoteStateException(
                    "单据 " + id + " 状态应为 " + expected + "，实际 " + note.getStatus());
        }
        return note;
    }

    /**
     * 校验行存在 + 属于指定主单.
     */
    private WmsInboundLine mustLineInNote(Long noteId, Long lineId) {
        WmsInboundLine line = mapper.selectLineById(lineId);
        if (line == null || !line.getNoteId().equals(noteId)) {
            throw new IllegalNoteStateException(
                    "入库单 " + noteId + " 下找不到行 " + lineId);
        }
        return line;
    }
}
