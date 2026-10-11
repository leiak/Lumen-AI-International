package com.lumen.wms.service;

import com.lumen.common.api.PageResult;
import com.lumen.wms.dto.CreateItemRequest;
import com.lumen.wms.dto.ItemVO;
import com.lumen.wms.dto.UpdateItemRequest;
import com.lumen.wms.entity.WmsItem;

/**
 * 货品主数据服务.
 */
public interface ItemService {

    /**
     * 分页查询货品.
     *
     * @param pageNum  页号（从 1 开始）
     * @param pageSize 每页大小
     * @param keyword  模糊匹配 sku / name / barcode，可空
     */
    PageResult<WmsItem> page(long pageNum, long pageSize, String keyword);

    /**
     * 按主键查询货品，未找到抛出 {@code ItemNotFoundException}.
     */
    WmsItem getById(Long id);

    /**
     * 新建货品. 唯一约束冲突时抛出 {@code WmsDuplicateCodeException}.
     */
    WmsItem create(CreateItemRequest req);

    /**
     * 更新货品. 唯一约束冲突时抛出 {@code WmsDuplicateCodeException}.
     */
    WmsItem update(Long id, UpdateItemRequest req);

    /**
     * 删除货品.
     */
    void delete(Long id);

    /**
     * 实体转视图对象.
     */
    ItemVO toVO(WmsItem entity);
}