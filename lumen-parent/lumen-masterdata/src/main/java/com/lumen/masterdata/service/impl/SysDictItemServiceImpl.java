package com.lumen.masterdata.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.masterdata.entity.SysDictItem;
import com.lumen.masterdata.mapper.SysDictItemMapper;
import com.lumen.masterdata.service.SysDictItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SysDictItemServiceImpl implements SysDictItemService {

    private final SysDictItemMapper itemMapper;

    @Override
    public PageResult<SysDictItem> page(long pageNum, long pageSize, String keyword) {
        Page<SysDictItem> p = Page.of(pageNum, pageSize);
        QueryWrapper<SysDictItem> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) qw.like("code", keyword).or().like("label", keyword);
        Page<SysDictItem> res = itemMapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), pageNum, pageSize);
    }

    @Override
    @Transactional
    public SysDictItem create(SysDictItem entity) {
        if (entity.getTenantId() == null) entity.setTenantId(TenantContext.require());
        itemMapper.insert(entity);
        return entity;
    }

    @Override
    public SysDictItem update(Long id, SysDictItem entity) {
        entity.setId(id);
        itemMapper.updateById(entity);
        return itemMapper.selectById(id);
    }

    @Override
    public SysDictItem getById(Long id) { return itemMapper.selectById(id); }

    @Override
    @Transactional
    public void delete(Long id) { itemMapper.deleteById(id); }
}