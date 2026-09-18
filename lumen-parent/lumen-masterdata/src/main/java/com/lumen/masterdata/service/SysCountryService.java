package com.lumen.masterdata.service;

import com.lumen.common.api.PageResult;
import com.lumen.masterdata.entity.SysCountry;

public interface SysCountryService {
    PageResult<SysCountry> page(long pageNum, long pageSize, String keyword);
    SysCountry create(SysCountry entity);
    SysCountry update(Long id, SysCountry entity);
    SysCountry getById(Long id);
    void delete(Long id);
}