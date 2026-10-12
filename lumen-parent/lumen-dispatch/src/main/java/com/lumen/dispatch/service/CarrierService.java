package com.lumen.dispatch.service;

import com.lumen.common.api.PageResult;
import com.lumen.dispatch.dto.CarrierDtos.CarrierVO;
import com.lumen.dispatch.dto.CarrierDtos.CreateCarrierRequest;
import com.lumen.dispatch.dto.CarrierDtos.QueryCarrierRequest;
import com.lumen.dispatch.dto.CarrierDtos.UpdateCarrierRequest;
import com.lumen.dispatch.entity.WmsCarrier;

/**
 * 承运人主数据服务.
 *
 * <p>承运人不参与 VEHICLE/DISPATCH_ORDER 状态机流转，{@code status}
 * 仅 ACTIVE/SUSPENDED 标记是否启用，无需 seed 状态机行.
 */
public interface CarrierService {

    PageResult<WmsCarrier> page(long current, long size, QueryCarrierRequest query);

    WmsCarrier get(Long id);

    WmsCarrier create(CreateCarrierRequest req);

    WmsCarrier update(Long id, UpdateCarrierRequest req);

    void delete(Long id);

    CarrierVO toVO(WmsCarrier entity);
}