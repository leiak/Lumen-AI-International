package com.lumen.masterdata.service;

import com.lumen.common.api.PageResult;
import com.lumen.masterdata.entity.SysDict;
import com.lumen.masterdata.entity.SysDictItem;

import java.util.List;

public interface SysDictService {
    PageResult<SysDict> page(long pageNum, long pageSize, String keyword);
    SysDict create(SysDict entity);
    SysDict update(Long id, SysDict entity);
    SysDict getById(Long id);
    void delete(Long id);
    List<SysDictItem> listItems(Long dictId);
}