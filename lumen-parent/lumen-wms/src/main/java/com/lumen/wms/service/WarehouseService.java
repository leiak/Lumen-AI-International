package com.lumen.wms.service;

import com.lumen.common.api.PageResult;
import com.lumen.wms.dto.CreateWarehouseRequest;
import com.lumen.wms.dto.UpdateWarehouseRequest;
import com.lumen.wms.dto.WarehouseVO;
import com.lumen.wms.entity.WmsWarehouse;

/**
 * 仓库主数据服务.
 */
public interface WarehouseService {

    /**
     * 分页查询仓库.
     *
     * @param pageNum  页号（从 1 开始）
     * @param pageSize 每页大小
     * @param keyword  模糊匹配 code / name，可空
     * @param status   按 status 精确过滤，可空
     * @return 分页结果
     */
    PageResult<WmsWarehouse> page(long pageNum, long pageSize, String keyword, String status);

    /**
     * 按主键查询仓库，未找到抛出 {@code WarehouseNotFoundException}.
     */
    WmsWarehouse getById(Long id);

    /**
     * 新建仓库. 唯一约束冲突时抛出 {@code WmsDuplicateCodeException}.
     */
    WmsWarehouse create(CreateWarehouseRequest req);

    /**
     * 更新仓库. 唯一约束冲突时抛出 {@code WmsDuplicateCodeException}.
     */
    WmsWarehouse update(Long id, UpdateWarehouseRequest req);

    /**
     * 删除仓库（逻辑删，由 MyBatis-Plus 逻辑删除字段处理）.
     */
    void delete(Long id);

    /**
     * 实体转视图对象.
     */
    WarehouseVO toVO(WmsWarehouse entity);
}