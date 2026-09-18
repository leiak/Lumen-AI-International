package com.lumen.masterdata.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.masterdata.entity.SysUom;
import com.lumen.masterdata.mapper.SysUomMapper;
import com.lumen.masterdata.service.SysUomService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SysUomServiceImpl implements SysUomService {

    private final SysUomMapper uomMapper;

    @Override
    public PageResult<SysUom> page(long pageNum, long pageSize, String keyword) {
        Page<SysUom> p = Page.of(pageNum, pageSize);
        QueryWrapper<SysUom> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) qw.like("code", keyword).or().like("name", keyword);
        Page<SysUom> res = uomMapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), pageNum, pageSize);
    }

    @Override
    @Transactional
    public SysUom create(SysUom entity) {
        if (entity.getTenantId() == null) entity.setTenantId(TenantContext.require());
        uomMapper.insert(entity);
        return entity;
    }

    @Override
    public SysUom update(Long id, SysUom entity) {
        entity.setId(id);
        uomMapper.updateById(entity);
        return uomMapper.selectById(id);
    }

    @Override
    public SysUom getById(Long id) { return uomMapper.selectById(id); }

    @Override
    @Transactional
    public void delete(Long id) { uomMapper.deleteById(id); }
}