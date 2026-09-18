package com.lumen.masterdata.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.masterdata.entity.SysCountry;
import com.lumen.masterdata.mapper.SysCountryMapper;
import com.lumen.masterdata.service.SysCountryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SysCountryServiceImpl implements SysCountryService {

    private final SysCountryMapper countryMapper;

    @Override
    public PageResult<SysCountry> page(long pageNum, long pageSize, String keyword) {
        Page<SysCountry> p = Page.of(pageNum, pageSize);
        QueryWrapper<SysCountry> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) qw.like("code", keyword).or().like("name_cn", keyword).or().like("name_en", keyword);
        Page<SysCountry> res = countryMapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), pageNum, pageSize);
    }

    @Override
    @Transactional
    public SysCountry create(SysCountry entity) {
        if (entity.getTenantId() == null) entity.setTenantId(TenantContext.require());
        countryMapper.insert(entity);
        return entity;
    }

    @Override
    public SysCountry update(Long id, SysCountry entity) {
        entity.setId(id);
        countryMapper.updateById(entity);
        return countryMapper.selectById(id);
    }

    @Override
    public SysCountry getById(Long id) { return countryMapper.selectById(id); }

    @Override
    @Transactional
    public void delete(Long id) { countryMapper.deleteById(id); }
}