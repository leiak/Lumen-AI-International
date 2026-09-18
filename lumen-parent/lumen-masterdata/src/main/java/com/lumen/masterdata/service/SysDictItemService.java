package com.lumen.masterdata.service;

import com.lumen.common.api.PageResult;
import com.lumen.masterdata.entity.SysDictItem;

public interface SysDictItemService {
    PageResult<SysDictItem> page(long pageNum, long pageSize, String keyword);
    SysDictItem create(SysDictItem entity);
    SysDictItem update(Long id, SysDictItem entity);
    SysDictItem getById(Long id);
    void delete(Long id);
}