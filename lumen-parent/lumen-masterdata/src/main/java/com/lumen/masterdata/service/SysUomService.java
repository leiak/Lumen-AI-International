package com.lumen.masterdata.service;

import com.lumen.common.api.PageResult;
import com.lumen.masterdata.entity.SysUom;

public interface SysUomService {
    PageResult<SysUom> page(long pageNum, long pageSize, String keyword);
    SysUom create(SysUom entity);
    SysUom update(Long id, SysUom entity);
    SysUom getById(Long id);
    void delete(Long id);
}