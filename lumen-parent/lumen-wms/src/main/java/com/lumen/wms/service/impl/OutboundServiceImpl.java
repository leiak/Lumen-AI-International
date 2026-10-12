package com.lumen.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.wms.WmsConstants;
import com.lumen.wms.approval.ThresholdEvaluator;
import com.lumen.wms.dto.AddOutboundLineRequest;
import com.lumen.wms.dto.CreateOutboundNoteRequest;
import com.lumen.wms.dto.OutboundLineDto;
import com.lumen.wms.dto.OutboundLineVO;
import com.lumen.wms.dto.OutboundNoteVO;
import com.lumen.wms.dto.UpdateOutboundNoteRequest;
import com.lumen.wms.entity.WmsCustomer;
import com.lumen.wms.entity.WmsItem;
import com.lumen.wms.entity.WmsLocation;
import com.lumen.wms.entity.WmsOutboundLine;
import com.lumen.wms.entity.WmsOutboundNote;
import com.lumen.wms.exception.IllegalNoteStateException;
import com.lumen.wms.exception.OutboundNoteNotFoundException;
import com.lumen.wms.exception.WmsDuplicateCodeException;
import com.lumen.wms.mapper.WmsCustomerMapper;
import com.lumen.wms.mapper.WmsItemMapper;
import com.lumen.wms.mapper.WmsLocationMapper;
import com.lumen.wms.mapper.WmsOutboundNoteMapper;
import com.lumen.wms.service.OutboundService;
import com.lumen.wms.service.WmsInventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 出库单主表 / 行表持久化服务实现（T4.1：基础 CRUD + T4.2：状态机）.
 *
 * <p>与 {@code InboundServiceImpl} 同形；行项目强制要求
 * {@code sourceLocationId}（出库必须拣货位）.
 * 状态机 DRAFT → PENDING_APPROVAL / PICKING → REVIEW → SHIPPED → DONE / CANCELLED
 * 由 {@code submit / approve / pickLine / ship / complete / cancel} 驱动；
 * submit 阶段按 {@link ThresholdEvaluator} 路由阈值，并对每行调用
 * {@code inventoryService.lockQty()} 锁库存.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboundServiceImpl implements OutboundService {

    private final WmsOutboundNoteMapper mapper;
    private final ThresholdEvaluator evaluator;
    private final WmsInventoryService inventoryService;
    private final WmsItemMapper itemMapper;
    private final WmsLocationMapper locationMapper;
    private final WmsCustomerMapper customerMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public PageResult<WmsOutboundNote> page(long pageNum, long pageSize, String keyword, String status) {
        Page<WmsOutboundNote> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<WmsOutboundNote> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.like(WmsOutboundNote::getNo, keyword);
        }
        if (status != null && !status.isBlank()) {
            qw.eq(WmsOutboundNote::getStatus, status);
        }
        qw.orderByDesc(WmsOutboundNote::getId);
        Page<WmsOutboundNote> res = mapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), pageNum, pageSize);
    }

    @Override
    public WmsOutboundNote getById(Long id) {
        WmsOutboundNote note = mapper.selectById(id);
        if (note == null) throw new OutboundNoteNotFoundException(id);
        return note;
    }

    @Override
    @Transactional
    public WmsOutboundNote create(CreateOutboundNoteRequest req) {
        WmsOutboundNote note = new WmsOutboundNote();
        BeanUtils.copyProperties(req, note);
        if (note.getTenantId() == null) {
            note.setTenantId(TenantContext.require());
        }
        if (note.getStatus() == null) {
            note.setStatus(WmsConstants.OUT_DRAFT);
        }
        try {
            mapper.insert(note);
        } catch (DuplicateKeyException e) {
            log.warn("duplicate outbound no: {}", req.getNo());
            throw new WmsDuplicateCodeException("outbound", req.getNo());
        }
        insertLines(note.getId(), req.getLines());
        return mapper.selectById(note.getId());
    }

    @Override
    @Transactional
    public WmsOutboundNote update(Long id, UpdateOutboundNoteRequest req) {
        WmsOutboundNote existing = getById(id);
        // 仅复制可变更字段：主单 no/customerId/warehouseId/status 不允许通过本接口修改
        BeanUtils.copyProperties(req, existing, "id", "tenantId", "createdAt",
                "no", "customerId", "warehouseId", "status",
                "requestedAt", "shippedAt");
        try {
            mapper.updateById(existing);
        } catch (DuplicateKeyException e) {
            // 出库单号不可变，但 BeanUtils 过滤后理论上不会触发；保留防御性代码
            log.warn("duplicate outbound no on update: {}", existing.getNo());
            throw new WmsDuplicateCodeException("outbound", existing.getNo());
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
        // getById 会抛 OutboundNoteNotFoundException
        getById(id);
        mapper.deleteById(id);
        mapper.deleteLinesByNoteId(id);
    }

    @Override
    @Transactional
    public WmsOutboundLine addLine(Long noteId, AddOutboundLineRequest req) {
        // 触发父级存在性校验
        getById(noteId);

        WmsOutboundLine line = new WmsOutboundLine();
        BeanUtils.copyProperties(req, line, "lineNo");
        line.setNoteId(noteId);

        if (req.getLineNo() != null) {
            line.setLineNo(req.getLineNo());
            // lineNo 冲突时让 DB 抛 DuplicateKey
        } else {
            line.setLineNo(nextLineNo(noteId));
        }
        if (line.getPickedQty() == null) {
            line.setPickedQty(BigDecimal.ZERO);
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
        WmsOutboundLine existing = mapper.selectLineById(lineId);
        if (existing == null || !existing.getNoteId().equals(noteId)) {
            throw new OutboundNoteNotFoundException(noteId);
        }
        mapper.deleteLineById(lineId);
    }

    @Override
    public List<WmsOutboundLine> listLines(Long noteId) {
        // 触发父级存在性校验
        getById(noteId);
        return mapper.selectLinesByNoteId(noteId);
    }

    @Override
    public OutboundNoteVO toVO(WmsOutboundNote note) {
        if (note == null) return null;
        OutboundNoteVO vo = new OutboundNoteVO();
        BeanUtils.copyProperties(note, vo);
        // 本方法不自动加载行项目 — 调用方按需 setLines(lineVOs(...))
        return vo;
    }

    @Override
    public List<OutboundLineVO> lineVOs(List<WmsOutboundLine> lines) {
        List<OutboundLineVO> result = new ArrayList<>();
        if (lines == null) return result;
        for (WmsOutboundLine line : lines) {
            OutboundLineVO lvo = new OutboundLineVO();
            BeanUtils.copyProperties(line, lvo);
            result.add(lvo);
        }
        return result;
    }

    // ====================================================================
    // T4.2 状态机 — submit / approve / pickLine / ship / complete / cancel
    // ====================================================================

    @Override
    @Transactional
    public WmsOutboundNote submit(Long id) {
        WmsOutboundNote note = mustInStatus(id, WmsConstants.OUT_DRAFT);
        List<WmsOutboundLine> lines = mapper.selectLinesByNoteId(id);
        if (lines.isEmpty()) {
            throw new IllegalNoteStateException("出库单至少要有 1 条行: id=" + id);
        }

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (WmsOutboundLine line : lines) {
            if (line.getQty() == null || line.getQty().signum() <= 0) {
                throw new IllegalNoteStateException(
                        "出库单行 qty 无效: id=" + id + " line=" + line.getId());
            }
            if (line.getSourceLocationId() == null) {
                throw new IllegalNoteStateException(
                        "出库单行缺少 sourceLocationId: id=" + id + " line=" + line.getId());
            }
            WmsLocation loc = locationMapper.selectById(line.getSourceLocationId());
            if (loc == null || !WmsConstants.LOC_ACTIVE.equals(loc.getStatus())) {
                throw new IllegalNoteStateException(
                        "源库位不可用: id=" + id + " line=" + line.getId()
                                + " locationId=" + line.getSourceLocationId()
                                + " status=" + (loc == null ? "NULL" : loc.getStatus()));
            }
            WmsItem item = itemMapper.selectById(line.getItemId());
            if (item == null) {
                throw new IllegalNoteStateException(
                        "找不到货品: id=" + id + " line=" + line.getId()
                                + " itemId=" + line.getItemId());
            }
            totalQty = totalQty.add(line.getQty());
            if (item.getUnitCost() != null) {
                totalAmount = totalAmount.add(item.getUnitCost().multiply(line.getQty()));
            }
            // reserve inventory — throws InsufficientInventoryException if not enough
            inventoryService.lockQty(note.getWarehouseId(),
                    line.getSourceLocationId(), line.getItemId(), line.getQty());
        }

        boolean needApproval = evaluator.requiresApproval(
                WmsConstants.BIZ_OUTBOUND, totalAmount, totalQty);
        String target = needApproval
                ? WmsConstants.OUT_PENDING_APPROVAL
                : WmsConstants.OUT_PICKING;

        note.setStatus(target);
        mapper.updateById(note);

        // TODO(PR-wms-approval-record): 创建审批记录 — 待 ApprovalRouter.chains
        //      配置 WMS_OUTBOUND 后，调用 ApprovalService.submit() 生成 approval_record.
        //      当前仅设置单据状态为 PENDING_APPROVAL，业务侧可在状态为 PENDING_APPROVAL 时
        //      通过手动审批按钮或后续 PR 接入 ApprovalService.submit() 来驱动审批流.
        log.info("Outbound note {} submitted: status={} qty={} amount={}",
                id, target, totalQty, totalAmount);
        return mapper.selectById(id);
    }

    @Override
    @Transactional
    public void approve(Long id) {
        WmsOutboundNote note = mustInStatus(id, WmsConstants.OUT_PENDING_APPROVAL);
        note.setStatus(WmsConstants.OUT_PICKING);
        mapper.updateById(note);
        log.info("Outbound note {} approved -> PICKING", id);
    }

    @Override
    @Transactional
    public WmsOutboundLine pickLine(Long noteId, Long lineId, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            throw new IllegalNoteStateException("拣货数量必须 > 0: line=" + lineId);
        }
        WmsOutboundNote note = mustInStatus(noteId, WmsConstants.OUT_PICKING);
        WmsOutboundLine line = mustLineInNote(noteId, lineId);

        BigDecimal cur = line.getPickedQty() == null ? BigDecimal.ZERO : line.getPickedQty();
        BigDecimal next = cur.add(qty);
        if (next.compareTo(line.getQty()) > 0) {
            throw new IllegalNoteStateException(
                    "拣货数量超过预期: line=" + lineId
                            + " expected=" + line.getQty()
                            + " picked=" + next);
        }
        if (line.getSourceLocationId() == null) {
            throw new IllegalNoteStateException("拣货行未指定源库位: line=" + lineId);
        }

        // 物理扣减库存（实际出库）
        inventoryService.subtractQty(note.getWarehouseId(),
                line.getSourceLocationId(), line.getItemId(), qty);
        // 释放锁定（submit 时预留的锁）
        inventoryService.releaseLock(note.getWarehouseId(),
                line.getSourceLocationId(), line.getItemId(), qty);
        line.setPickedQty(next);
        mapper.updateLineById(line);

        // 全行拣完 → REVIEW
        List<WmsOutboundLine> allLines = mapper.selectLinesByNoteId(noteId);
        boolean allPicked = allLines.stream().allMatch(l ->
                l.getPickedQty() != null && l.getPickedQty().compareTo(l.getQty()) >= 0);
        if (allPicked) {
            note.setStatus(WmsConstants.OUT_REVIEW);
            mapper.updateById(note);
            log.info("Outbound note {} -> REVIEW (all lines picked)", noteId);
        }
        return mapper.selectLineById(lineId);
    }

    @Override
    @Transactional
    public WmsOutboundNote ship(Long noteId, String carrier, String trackingNo) {
        if (carrier == null || carrier.isBlank()) {
            throw new IllegalNoteStateException("承运商不能为空: noteId=" + noteId);
        }
        if (trackingNo == null || trackingNo.isBlank()) {
            throw new IllegalNoteStateException("运单号不能为空: noteId=" + noteId);
        }
        WmsOutboundNote note = mustInStatus(noteId, WmsConstants.OUT_REVIEW);
        note.setStatus(WmsConstants.OUT_SHIPPED);
        note.setCarrier(carrier);
        note.setTrackingNo(trackingNo);
        mapper.updateById(note);
        log.info("Outbound note {} shipped: carrier={} tracking={}", noteId, carrier, trackingNo);

        // Publish OutboundShippedEvent for lumen-dispatch listener to auto-create
        // a PENDING_DISPATCH delivery order (Iteration 4.1 PR4).
        // 事件本身携带客户地址/联系人/电话/行数, listener 不再跨模块查询 WMS.
        try {
            WmsCustomer customer = note.getCustomerId() != null
                    ? customerMapper.selectById(note.getCustomerId()) : null;
            int stopCount = mapper.selectLinesByNoteId(noteId).size();
            if (stopCount < 1) stopCount = 1;

            com.lumen.dispatch.event.OutboundShippedEvent event =
                    new com.lumen.dispatch.event.OutboundShippedEvent(
                            this,
                            note.getTenantId(),
                            note.getId(),
                            note.getNo(),
                            note.getCustomerId(),
                            note.getWarehouseId(),
                            customer != null ? customer.getAddress() : null,
                            customer != null ? customer.getContact() : null,
                            customer != null ? customer.getPhone() : null,
                            stopCount);
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            // Listener misconfig must not break WMS outbound flow
            log.warn("Failed to publish OutboundShippedEvent for note {}: {}",
                    noteId, e.getMessage());
        }
        return mapper.selectById(noteId);
    }

    @Override
    @Transactional
    public WmsOutboundNote complete(Long id) {
        WmsOutboundNote note = mustInStatus(id, WmsConstants.OUT_SHIPPED);
        List<WmsOutboundLine> lines = mapper.selectLinesByNoteId(id);
        for (WmsOutboundLine line : lines) {
            // flushLock: qty -= locked_qty, locked_qty = 0
            if (line.getSourceLocationId() != null
                    && line.getPickedQty() != null
                    && line.getPickedQty().signum() > 0) {
                inventoryService.flushLock(note.getWarehouseId(),
                        line.getSourceLocationId(), line.getItemId());
            }
        }
        note.setStatus(WmsConstants.OUT_DONE);
        note.setShippedAt(LocalDateTime.now());
        mapper.updateById(note);
        log.info("Outbound note {} -> DONE (locks flushed)", id);
        return mapper.selectById(id);
    }

    @Override
    @Transactional
    public WmsOutboundNote cancel(Long id) {
        WmsOutboundNote note = getById(id);
        String s = note.getStatus();
        if (WmsConstants.OUT_DONE.equals(s) || WmsConstants.OUT_CANCELLED.equals(s)) {
            throw new IllegalNoteStateException(
                    "DONE/CANCELLED 单据不可取消: id=" + id + " status=" + s);
        }
        // Release any held locks for lines in PICKING/REVIEW/SHIPPED states
        if (WmsConstants.OUT_PICKING.equals(s)
                || WmsConstants.OUT_REVIEW.equals(s)
                || WmsConstants.OUT_SHIPPED.equals(s)) {
            List<WmsOutboundLine> lines = mapper.selectLinesByNoteId(id);
            for (WmsOutboundLine line : lines) {
                if (line.getSourceLocationId() == null) continue;
                BigDecimal cur = line.getPickedQty() == null ? BigDecimal.ZERO : line.getPickedQty();
                BigDecimal remaining = line.getQty().subtract(cur);
                if (remaining.signum() > 0) {
                    try {
                        inventoryService.releaseLock(note.getWarehouseId(),
                                line.getSourceLocationId(), line.getItemId(), remaining);
                    } catch (Exception e) {
                        log.warn("Failed to release lock for outbound cancel: note={} line={} qty={}",
                                id, line.getId(), remaining, e);
                    }
                }
            }
        }
        note.setStatus(WmsConstants.OUT_CANCELLED);
        mapper.updateById(note);
        log.info("Outbound note {} cancelled: prevStatus={}", id, s);
        return mapper.selectById(id);
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private void insertLines(Long noteId, List<OutboundLineDto> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return;
        }
        Long tenantId = TenantContext.require();
        int autoLineNo = 1;
        for (OutboundLineDto dto : dtos) {
            // 出库单行必须指定 sourceLocationId（DTO 校验已保证非空）
            WmsOutboundLine line = new WmsOutboundLine();
            line.setTenantId(tenantId);
            line.setNoteId(noteId);
            line.setLineNo(autoLineNo++);
            line.setItemId(dto.getItemId());
            line.setQty(dto.getQty());
            line.setPickedQty(BigDecimal.ZERO);
            line.setSourceLocationId(dto.getSourceLocationId());
            mapper.insertLine(line);
        }
    }

    private int nextLineNo(Long noteId) {
        List<WmsOutboundLine> existing = mapper.selectLinesByNoteId(noteId);
        int max = 0;
        for (WmsOutboundLine l : existing) {
            if (l.getLineNo() != null && l.getLineNo() > max) {
                max = l.getLineNo();
            }
        }
        return max + 1;
    }

    /**
     * 校验单据状态 — 不符则抛 {@link IllegalNoteStateException}.
     */
    private WmsOutboundNote mustInStatus(Long id, String expected) {
        WmsOutboundNote note = getById(id);
        if (!expected.equals(note.getStatus())) {
            throw new IllegalNoteStateException(
                    "单据 " + id + " 状态应为 " + expected + "，实际 " + note.getStatus());
        }
        return note;
    }

    /**
     * 校验行存在 + 属于指定主单.
     */
    private WmsOutboundLine mustLineInNote(Long noteId, Long lineId) {
        WmsOutboundLine line = mapper.selectLineById(lineId);
        if (line == null || !line.getNoteId().equals(noteId)) {
            throw new IllegalNoteStateException(
                    "出库单 " + noteId + " 下找不到行 " + lineId);
        }
        return line;
    }
}
