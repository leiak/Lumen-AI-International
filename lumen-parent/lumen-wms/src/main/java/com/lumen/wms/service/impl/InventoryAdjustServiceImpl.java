package com.lumen.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.wms.WmsConstants;
import com.lumen.wms.approval.ThresholdEvaluator;
import com.lumen.wms.dto.AddInventoryAdjustLineRequest;
import com.lumen.wms.dto.CreateInventoryAdjustRequest;
import com.lumen.wms.dto.InventoryAdjustLineDto;
import com.lumen.wms.dto.InventoryAdjustLineVO;
import com.lumen.wms.dto.InventoryAdjustVO;
import com.lumen.wms.dto.UpdateInventoryAdjustRequest;
import com.lumen.wms.entity.WmsInventoryAdjust;
import com.lumen.wms.entity.WmsInventoryAdjustLine;
import com.lumen.wms.entity.WmsItem;
import com.lumen.wms.exception.IllegalNoteStateException;
import com.lumen.wms.exception.InventoryAdjustNotFoundException;
import com.lumen.wms.exception.WmsDuplicateCodeException;
import com.lumen.wms.mapper.WmsInventoryAdjustMapper;
import com.lumen.wms.mapper.WmsItemMapper;
import com.lumen.wms.service.InventoryAdjustService;
import com.lumen.wms.service.WmsInventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 库存调整单主表 / 行表持久化服务实现.
 *
 * <p>T3.1：基础 CRUD（create / update / delete / addLine / removeLine / page）。
 * T5.1：状态机 — {@code submit / approve / reject / execute / cancel}；
 * submit 按 {@link ThresholdEvaluator} 路由阈值，
 * execute 对每行调用 {@code inventoryService.adjustDelta()} 落地库存账本.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryAdjustServiceImpl implements InventoryAdjustService {

    private final WmsInventoryAdjustMapper mapper;
    private final ThresholdEvaluator evaluator;
    private final WmsInventoryService inventoryService;
    private final WmsItemMapper itemMapper;

    @Override
    public PageResult<WmsInventoryAdjust> page(long pageNum, long pageSize, String keyword,
                                                Long warehouseId, String status) {
        Page<WmsInventoryAdjust> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<WmsInventoryAdjust> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.like(WmsInventoryAdjust::getNo, keyword);
        }
        if (warehouseId != null) {
            qw.eq(WmsInventoryAdjust::getWarehouseId, warehouseId);
        }
        if (status != null && !status.isBlank()) {
            qw.eq(WmsInventoryAdjust::getStatus, status);
        }
        qw.orderByDesc(WmsInventoryAdjust::getId);
        Page<WmsInventoryAdjust> res = mapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), pageNum, pageSize);
    }

    @Override
    public WmsInventoryAdjust getById(Long id) {
        WmsInventoryAdjust adj = mapper.selectById(id);
        if (adj == null) throw new InventoryAdjustNotFoundException(id);
        return adj;
    }

    @Override
    @Transactional
    public WmsInventoryAdjust create(CreateInventoryAdjustRequest req) {
        WmsInventoryAdjust adj = new WmsInventoryAdjust();
        BeanUtils.copyProperties(req, adj);
        if (adj.getTenantId() == null) {
            adj.setTenantId(TenantContext.require());
        }
        if (adj.getStatus() == null) {
            adj.setStatus("DRAFT");
        }
        try {
            mapper.insert(adj);
        } catch (DuplicateKeyException e) {
            log.warn("duplicate adjust no: {}", req.getNo());
            throw new WmsDuplicateCodeException("adjust", req.getNo());
        }
        insertLines(adj.getId(), req.getLines());
        return mapper.selectById(adj.getId());
    }

    @Override
    @Transactional
    public WmsInventoryAdjust update(Long id, UpdateInventoryAdjustRequest req) {
        WmsInventoryAdjust existing = getById(id);
        BeanUtils.copyProperties(req, existing, "id", "tenantId", "createdAt");
        try {
            mapper.updateById(existing);
        } catch (DuplicateKeyException e) {
            log.warn("duplicate adjust no on update: {}", req.getNo());
            throw new WmsDuplicateCodeException("adjust", req.getNo());
        }
        if (req.hasLines()) {
            mapper.deleteLinesByAdjustId(id);
            if (req.hasNonEmptyLines()) {
                insertLines(id, req.getLines());
            }
        }
        return mapper.selectById(id);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        getById(id);
        mapper.deleteById(id);
        mapper.deleteLinesByAdjustId(id);
    }

    @Override
    @Transactional
    public WmsInventoryAdjustLine addLine(Long adjustId, AddInventoryAdjustLineRequest req) {
        getById(adjustId);

        WmsInventoryAdjustLine line = new WmsInventoryAdjustLine();
        BeanUtils.copyProperties(req, line, "lineNo");
        line.setAdjustId(adjustId);

        if (req.getLineNo() != null) {
            line.setLineNo(req.getLineNo());
        } else {
            line.setLineNo(nextLineNo(adjustId));
        }
        if (line.getTenantId() == null) {
            line.setTenantId(TenantContext.require());
        }
        mapper.insertLine(line);
        return line;
    }

    @Override
    @Transactional
    public void removeLine(Long adjustId, Long lineId) {
        getById(adjustId);
        WmsInventoryAdjustLine existing = mapper.selectLineById(lineId);
        if (existing == null || !existing.getAdjustId().equals(adjustId)) {
            throw new InventoryAdjustNotFoundException(adjustId);
        }
        mapper.deleteLineById(lineId);
    }

    @Override
    public List<WmsInventoryAdjustLine> listLines(Long adjustId) {
        getById(adjustId);
        return mapper.selectLinesByAdjustId(adjustId);
    }

    @Override
    public InventoryAdjustVO toVO(WmsInventoryAdjust entity) {
        if (entity == null) return null;
        InventoryAdjustVO vo = new InventoryAdjustVO();
        BeanUtils.copyProperties(entity, vo);
        List<WmsInventoryAdjustLine> lines = mapper.selectLinesByAdjustId(entity.getId());
        vo.setLines(lineVOs(lines));
        return vo;
    }

    @Override
    public List<InventoryAdjustLineVO> lineVOs(List<WmsInventoryAdjustLine> lines) {
        List<InventoryAdjustLineVO> result = new ArrayList<>();
        if (lines == null) return result;
        for (WmsInventoryAdjustLine line : lines) {
            InventoryAdjustLineVO lvo = new InventoryAdjustLineVO();
            BeanUtils.copyProperties(line, lvo);
            result.add(lvo);
        }
        return result;
    }

    // ====================================================================
    // T5.1 状态机 — submit / approve / reject / execute / cancel
    // ====================================================================

    @Override
    @Transactional
    public WmsInventoryAdjust submit(Long noteId) {
        WmsInventoryAdjust note = mustInStatus(noteId, WmsConstants.ADJ_DRAFT);
        List<WmsInventoryAdjustLine> lines = mapper.selectLinesByAdjustId(noteId);
        if (lines.isEmpty()) {
            throw new IllegalNoteStateException("库存调整单至少要有 1 条行: id=" + noteId);
        }

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (WmsInventoryAdjustLine line : lines) {
            if (line.getDeltaQty() == null || line.getDeltaQty().signum() == 0) {
                throw new IllegalNoteStateException(
                        "库存调整单行 deltaQty 无效: id=" + noteId + " line=" + line.getId());
            }
            BigDecimal absDelta = line.getDeltaQty().abs();
            totalQty = totalQty.add(absDelta);

            // 行本身没有 unitPrice 字段 — 借 item.unitCost 折算金额；缺失则按原 qty 数量计入
            WmsItem item = itemMapper.selectById(line.getItemId());
            if (item != null && item.getUnitCost() != null) {
                totalAmount = totalAmount.add(item.getUnitCost().multiply(absDelta));
            } else {
                totalAmount = totalAmount.add(absDelta);
            }
        }

        boolean needApproval = evaluator.requiresApproval(
                WmsConstants.BIZ_ADJUST, totalAmount, totalQty);
        String target = needApproval
                ? WmsConstants.ADJ_PENDING_APPROVAL
                : WmsConstants.ADJ_APPROVED;

        note.setStatus(target);
        mapper.updateById(note);

        // TODO(PR5-iter): ApprovalService.submit() integration — requires ApprovalRouter config
        //  for WMS_INV_ADJUST bizKind. For now threshold routing is enforced here only.
        log.info("Adjust note {} submitted: status={} qty={} amount={}",
                noteId, target, totalQty, totalAmount);
        return mapper.selectById(noteId);
    }

    @Override
    @Transactional
    public WmsInventoryAdjust approve(Long noteId) {
        WmsInventoryAdjust note = mustInStatus(noteId, WmsConstants.ADJ_PENDING_APPROVAL);
        note.setStatus(WmsConstants.ADJ_APPROVED);
        mapper.updateById(note);
        log.info("Adjust note {} approved -> APPROVED", noteId);
        return mapper.selectById(noteId);
    }

    @Override
    @Transactional
    public WmsInventoryAdjust reject(Long noteId) {
        WmsInventoryAdjust note = mustInStatus(noteId, WmsConstants.ADJ_PENDING_APPROVAL);
        note.setStatus(WmsConstants.ADJ_REJECTED);
        mapper.updateById(note);
        log.info("Adjust note {} rejected -> REJECTED", noteId);
        return mapper.selectById(noteId);
    }

    @Override
    @Transactional
    public WmsInventoryAdjust execute(Long noteId) {
        WmsInventoryAdjust note = mustInStatus(noteId, WmsConstants.ADJ_APPROVED);
        List<WmsInventoryAdjustLine> lines = mapper.selectLinesByAdjustId(noteId);
        if (lines.isEmpty()) {
            throw new IllegalNoteStateException("库存调整单至少要有 1 条行: id=" + noteId);
        }

        for (WmsInventoryAdjustLine line : lines) {
            if (line.getDeltaQty() == null || line.getDeltaQty().signum() == 0) {
                throw new IllegalNoteStateException(
                        "库存调整单行 deltaQty 无效: id=" + noteId + " line=" + line.getId());
            }
            inventoryService.adjustDelta(note.getWarehouseId(),
                    line.getLocationId(), line.getItemId(), line.getDeltaQty());
        }

        note.setStatus(WmsConstants.ADJ_DONE);
        mapper.updateById(note);
        log.info("Adjust note {} -> DONE ({} deltas applied)", noteId, lines.size());
        return mapper.selectById(noteId);
    }

    @Override
    @Transactional
    public WmsInventoryAdjust cancel(Long noteId) {
        WmsInventoryAdjust note = getById(noteId);
        String s = note.getStatus();
        if (WmsConstants.ADJ_DONE.equals(s)
                || WmsConstants.ADJ_REJECTED.equals(s)
                || WmsConstants.ADJ_CANCELLED.equals(s)) {
            throw new IllegalNoteStateException(
                    "DONE/REJECTED/CANCELLED 单据不可取消: id=" + noteId + " status=" + s);
        }
        note.setStatus(WmsConstants.ADJ_CANCELLED);
        mapper.updateById(note);
        log.info("Adjust note {} cancelled: prevStatus={}", noteId, s);
        return mapper.selectById(noteId);
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private void insertLines(Long adjustId, List<InventoryAdjustLineDto> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return;
        }
        Long tenantId = TenantContext.require();
        int autoLineNo = 1;
        for (InventoryAdjustLineDto dto : dtos) {
            WmsInventoryAdjustLine line = new WmsInventoryAdjustLine();
            line.setTenantId(tenantId);
            line.setAdjustId(adjustId);
            line.setLineNo(autoLineNo++);
            line.setItemId(dto.getItemId());
            line.setLocationId(dto.getLocationId());
            line.setDeltaQty(dto.getDeltaQty());
            line.setReasonDetail(dto.getReasonDetail());
            mapper.insertLine(line);
        }
    }

    private int nextLineNo(Long adjustId) {
        List<WmsInventoryAdjustLine> existing = mapper.selectLinesByAdjustId(adjustId);
        int max = 0;
        for (WmsInventoryAdjustLine l : existing) {
            if (l.getLineNo() != null && l.getLineNo() > max) {
                max = l.getLineNo();
            }
        }
        return max + 1;
    }

    /**
     * 校验单据状态 — 不符则抛 {@link IllegalNoteStateException}.
     */
    private WmsInventoryAdjust mustInStatus(Long id, String expected) {
        WmsInventoryAdjust note = getById(id);
        if (!expected.equals(note.getStatus())) {
            throw new IllegalNoteStateException(
                    "单据 " + id + " 状态应为 " + expected + "，实际 " + note.getStatus());
        }
        return note;
    }

    /**
     * 校验行存在 + 属于指定主单.
     */
    private WmsInventoryAdjustLine mustLineInNote(Long noteId, Long lineId) {
        WmsInventoryAdjustLine line = mapper.selectLineById(lineId);
        if (line == null || !line.getAdjustId().equals(noteId)) {
            throw new IllegalNoteStateException(
                    "库存调整单 " + noteId + " 下找不到行 " + lineId);
        }
        return line;
    }
}
