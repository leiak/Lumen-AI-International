package com.lumen.wms.service;

import com.lumen.common.api.PageResult;
import com.lumen.wms.dto.CreateCustomerRequest;
import com.lumen.wms.dto.CustomerVO;
import com.lumen.wms.dto.UpdateCustomerRequest;
import com.lumen.wms.entity.WmsCustomer;

/**
 * 客户主数据服务.
 */
public interface CustomerService {

    /**
     * 分页查询客户.
     *
     * @param pageNum  页号（从 1 开始）
     * @param pageSize 每页大小
     * @param keyword  模糊匹配 code / name，可空
     */
    PageResult<WmsCustomer> page(long pageNum, long pageSize, String keyword);

    /**
     * 按主键查询客户，未找到抛出 {@code CustomerNotFoundException}.
     */
    WmsCustomer getById(Long id);

    /**
     * 新建客户. 唯一约束冲突时抛出 {@code WmsDuplicateCodeException}.
     */
    WmsCustomer create(CreateCustomerRequest req);

    /**
     * 更新客户. 唯一约束冲突时抛出 {@code WmsDuplicateCodeException}.
     */
    WmsCustomer update(Long id, UpdateCustomerRequest req);

    /**
     * 删除客户.
     */
    void delete(Long id);

    /**
     * 实体转视图对象.
     */
    CustomerVO toVO(WmsCustomer entity);
}