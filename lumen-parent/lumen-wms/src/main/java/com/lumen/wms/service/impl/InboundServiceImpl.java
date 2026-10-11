package com.lumen.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.wms.dto.AddInboundLineRequest;
import com.lumen.wms.dto.CreateInboundNoteRequest;
import com.lumen.wms.dto.InboundLineDto;
import com.lumen.wms.dto.InboundLineVO;
import com.lumen.wms.dto.InboundNoteVO;
import com.lumen.wms.dto.UpdateInboundNoteRequest;
import com.lumen.wms.entity.WmsInboundLine;
import com.lumen.wms.entity.WmsInboundNote;
import com.lumen.wms.exception.InboundNoteNotFoundException;
import com.lumen.wms.exception.WmsDuplicateCodeException;
import com.lumen.wms.mapper.WmsInboundNoteMapper;
import com.lumen.wms.service.InboundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 入库单主表 / 行表持久化服务实现（T3.1：基础 CRUD）.
 *
 * <p>本类实现仅维护入库单主表与行表之间的"创建 / 更新 / 删除"操作；
 * 不参与收货 / 上架 / 状态机的业务流（这些由 T3.4 实现）.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboundServiceImpl implements InboundService {

    private final WmsInboundNoteMapper mapper;

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
}
