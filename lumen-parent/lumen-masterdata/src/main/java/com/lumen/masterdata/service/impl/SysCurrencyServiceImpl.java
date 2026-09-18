package com.lumen.masterdata.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.masterdata.entity.SysCurrency;
import com.lumen.masterdata.mapper.SysCurrencyMapper;
import com.lumen.masterdata.service.SysCurrencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SysCurrencyServiceImpl implements SysCurrencyService {

    private final SysCurrencyMapper currencyMapper;

    @Override
    public PageResult<SysCurrency> page(long pageNum, long pageSize, String keyword) {
        Page<SysCurrency> p = Page.of(pageNum, pageSize);
        QueryWrapper<SysCurrency> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) qw.like("code", keyword).or().like("name", keyword);
        Page<SysCurrency> res = currencyMapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), pageNum, pageSize);
    }

    @Override
    @Transactional
    public SysCurrency create(SysCurrency entity) {
        if (entity.getTenantId() == null) entity.setTenantId(TenantContext.require());
        currencyMapper.insert(entity);
        return entity;
    }

    @Override
    public SysCurrency update(Long id, SysCurrency entity) {
        entity.setId(id);
        currencyMapper.updateById(entity);
        return currencyMapper.selectById(id);
    }

    @Override
    public SysCurrency getById(Long id) { return currencyMapper.selectById(id); }

    @Override
    @Transactional
    public void delete(Long id) { currencyMapper.deleteById(id); }
}