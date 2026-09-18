package com.lumen.masterdata.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.masterdata.entity.SysDict;
import com.lumen.masterdata.entity.SysDictItem;
import com.lumen.masterdata.mapper.SysDictItemMapper;
import com.lumen.masterdata.mapper.SysDictMapper;
import com.lumen.masterdata.service.SysDictService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SysDictServiceImpl implements SysDictService {

    private final SysDictMapper dictMapper;
    private final SysDictItemMapper itemMapper;

    @Override
    public PageResult<SysDict> page(long pageNum, long pageSize, String keyword) {
        Page<SysDict> p = Page.of(pageNum, pageSize);
        QueryWrapper<SysDict> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) qw.like("code", keyword).or().like("name", keyword);
        Page<SysDict> res = dictMapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), pageNum, pageSize);
    }

    @Override
    @Transactional
    public SysDict create(SysDict entity) {
        if (entity.getTenantId() == null) entity.setTenantId(TenantContext.require());
        dictMapper.insert(entity);
        return entity;
    }

    @Override
    public SysDict update(Long id, SysDict entity) {
        entity.setId(id);
        dictMapper.updateById(entity);
        return dictMapper.selectById(id);
    }

    @Override
    public SysDict getById(Long id) { return dictMapper.selectById(id); }

    @Override
    @Transactional
    public void delete(Long id) { dictMapper.deleteById(id); }

    @Override
    public List<SysDictItem> listItems(Long dictId) {
        return itemMapper.selectList(new QueryWrapper<SysDictItem>().eq("dict_id", dictId).orderByAsc("sort_order"));
    }
}