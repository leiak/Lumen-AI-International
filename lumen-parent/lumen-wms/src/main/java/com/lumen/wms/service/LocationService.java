package com.lumen.wms.service;

import com.lumen.common.api.PageResult;
import com.lumen.wms.dto.CreateLocationRequest;
import com.lumen.wms.dto.LocationVO;
import com.lumen.wms.dto.UpdateLocationRequest;
import com.lumen.wms.entity.WmsLocation;

import java.util.List;

/**
 * 库位主数据服务.
 */
public interface LocationService {

    /**
     * 分页查询库位.
     *
     * @param pageNum     页号（从 1 开始）
     * @param pageSize    每页大小
     * @param warehouseId 按所属仓库过滤，可空
     * @param keyword     模糊匹配 code / zone，可空
     * @param status      按 status 精确过滤，可空
     */
    PageResult<WmsLocation> page(long pageNum, long pageSize, Long warehouseId, String keyword, String status);

    /**
     * 按主键查询库位，未找到抛出 {@code LocationNotFoundException}.
     */
    WmsLocation getById(Long id);

    /**
     * 新建库位. 唯一约束冲突时抛出 {@code WmsDuplicateCodeException}.
     */
    WmsLocation create(CreateLocationRequest req);

    /**
     * 更新库位. 唯一约束冲突时抛出 {@code WmsDuplicateCodeException}.
     */
    WmsLocation update(Long id, UpdateLocationRequest req);

    /**
     * 删除库位.
     */
    void delete(Long id);

    /**
     * 按仓库列出全部库位（不分页，常用于下拉）.
     */
    List<WmsLocation> listByWarehouse(Long warehouseId);

    /**
     * 实体转视图对象.
     */
    LocationVO toVO(WmsLocation entity);
}