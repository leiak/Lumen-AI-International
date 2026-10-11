package com.lumen.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.wms.dto.AddInventoryAdjustLineRequest;
import com.lumen.wms.dto.CreateInventoryAdjustRequest;
import com.lumen.wms.dto.InventoryAdjustLineDto;
import com.lumen.wms.dto.InventoryAdjustLineVO;
import com.lumen.wms.dto.InventoryAdjustVO;
import com.lumen.wms.dto.UpdateInventoryAdjustRequest;
import com.lumen.wms.entity.WmsInventoryAdjust;
import com.lumen.wms.entity.WmsInventoryAdjustLine;
import com.lumen.wms.exception.InventoryAdjustNotFoundException;
import com.lumen.wms.exception.WmsDuplicateCodeException;
import com.lumen.wms.mapper.WmsInventoryAdjustMapper;
import com.lumen.wms.service.InventoryAdjustService;
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
 * 库存调整单主表 / 行表持久化服务实现（T3.1：基础 CRUD）.
 *
 * <p>与 InboundServiceImpl 同形，审批 / 账本影响由 T5.1 接入.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryAdjustServiceImpl implements InventoryAdjustService {

    private final WmsInventoryAdjustMapper mapper;

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
}
