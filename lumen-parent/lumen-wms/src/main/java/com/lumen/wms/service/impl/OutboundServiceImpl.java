package com.lumen.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.wms.WmsConstants;
import com.lumen.wms.dto.AddOutboundLineRequest;
import com.lumen.wms.dto.CreateOutboundNoteRequest;
import com.lumen.wms.dto.OutboundLineDto;
import com.lumen.wms.dto.OutboundLineVO;
import com.lumen.wms.dto.OutboundNoteVO;
import com.lumen.wms.dto.UpdateOutboundNoteRequest;
import com.lumen.wms.entity.WmsOutboundLine;
import com.lumen.wms.entity.WmsOutboundNote;
import com.lumen.wms.exception.OutboundNoteNotFoundException;
import com.lumen.wms.exception.WmsDuplicateCodeException;
import com.lumen.wms.mapper.WmsOutboundNoteMapper;
import com.lumen.wms.service.OutboundService;
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
 * 出库单主表 / 行表持久化服务实现（T4.1：基础 CRUD）.
 *
 * <p>与 {@code InboundServiceImpl} (T3.1) 同形；行项目强制要求
 * {@code sourceLocationId}（出库必须拣货位）.
 * 状态机 — submit / approve / cancel / pickLine / shipLine / close —
 * 由 T4.2 引入.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboundServiceImpl implements OutboundService {

    private final WmsOutboundNoteMapper mapper;

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
}
