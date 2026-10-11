package com.lumen.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.wms.dto.CreateItemRequest;
import com.lumen.wms.dto.ItemVO;
import com.lumen.wms.dto.UpdateItemRequest;
import com.lumen.wms.entity.WmsItem;
import com.lumen.wms.exception.ItemNotFoundException;
import com.lumen.wms.exception.WmsDuplicateCodeException;
import com.lumen.wms.mapper.WmsItemMapper;
import com.lumen.wms.service.ItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 货品主数据服务实现.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemServiceImpl implements ItemService {

    private final WmsItemMapper mapper;

    @Override
    public PageResult<WmsItem> page(long pageNum, long pageSize, String keyword) {
        Page<WmsItem> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<WmsItem> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(WmsItem::getSku, keyword)
                    .or().like(WmsItem::getName, keyword)
                    .or().like(WmsItem::getBarcode, keyword));
        }
        qw.orderByDesc(WmsItem::getId);
        Page<WmsItem> res = mapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), pageNum, pageSize);
    }

    @Override
    public WmsItem getById(Long id) {
        WmsItem item = mapper.selectById(id);
        if (item == null) throw new ItemNotFoundException(id);
        return item;
    }

    @Override
    @Transactional
    public WmsItem create(CreateItemRequest req) {
        WmsItem entity = new WmsItem();
        BeanUtils.copyProperties(req, entity);
        if (entity.getTenantId() == null) {
            entity.setTenantId(TenantContext.require());
        }
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException e) {
            log.warn("duplicate item sku: {}", req.getSku());
            throw new WmsDuplicateCodeException("item", req.getSku());
        }
        return entity;
    }

    @Override
    @Transactional
    public WmsItem update(Long id, UpdateItemRequest req) {
        WmsItem existing = getById(id);
        BeanUtils.copyProperties(req, existing, "id", "tenantId", "createdAt");
        try {
            mapper.updateById(existing);
        } catch (DuplicateKeyException e) {
            log.warn("duplicate item sku on update: {}", req.getSku());
            throw new WmsDuplicateCodeException("item", req.getSku());
        }
        return mapper.selectById(id);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        getById(id);
        mapper.deleteById(id);
    }

    @Override
    public ItemVO toVO(WmsItem entity) {
        if (entity == null) return null;
        ItemVO vo = new ItemVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}