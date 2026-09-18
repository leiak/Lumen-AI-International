package com.lumen.masterdata.service;

import com.lumen.common.api.PageResult;
import com.lumen.masterdata.entity.SysCurrency;

public interface SysCurrencyService {
    PageResult<SysCurrency> page(long pageNum, long pageSize, String keyword);
    SysCurrency create(SysCurrency entity);
    SysCurrency update(Long id, SysCurrency entity);
    SysCurrency getById(Long id);
    void delete(Long id);
}