package com.lumen.wms.service;

import com.lumen.common.api.PageResult;
import com.lumen.wms.dto.AddInventoryAdjustLineRequest;
import com.lumen.wms.dto.CreateInventoryAdjustRequest;
import com.lumen.wms.dto.InventoryAdjustLineVO;
import com.lumen.wms.dto.InventoryAdjustVO;
import com.lumen.wms.dto.UpdateInventoryAdjustRequest;
import com.lumen.wms.entity.WmsInventoryAdjust;
import com.lumen.wms.entity.WmsInventoryAdjustLine;

import java.util.List;

/**
 * 库存调整单服务接口（T3.1: 基础 CRUD + 持久化）.
 *
 * <p>审批流 + 库存账本写入由 T5.1 接入，本接口只关心主单 + 行的读写.
 */
public interface InventoryAdjustService {

    PageResult<WmsInventoryAdjust> page(long pageNum, long pageSize, String keyword,
                                        Long warehouseId, String status);

    WmsInventoryAdjust getById(Long id);

    WmsInventoryAdjust create(CreateInventoryAdjustRequest req);

    WmsInventoryAdjust update(Long id, UpdateInventoryAdjustRequest req);

    void delete(Long id);

    WmsInventoryAdjustLine addLine(Long adjustId, AddInventoryAdjustLineRequest req);

    void removeLine(Long adjustId, Long lineId);

    List<WmsInventoryAdjustLine> listLines(Long adjustId);

    InventoryAdjustVO toVO(WmsInventoryAdjust entity);

    List<InventoryAdjustLineVO> lineVOs(List<WmsInventoryAdjustLine> lines);
}
