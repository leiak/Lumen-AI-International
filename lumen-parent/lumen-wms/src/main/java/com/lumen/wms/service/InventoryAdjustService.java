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
 * 库存调整单服务接口（T3.1 基础 CRUD + T5.1 状态机）.
 *
 * <p>状态机：DRAFT → PENDING_APPROVAL/APPROVED → DONE / REJECTED / CANCELLED。
 * {@code submit} 按 {@link ThresholdEvaluator} 阈值路由到 PENDING_APPROVAL 或 APPROVED，
 * {@code execute} 对每行调用 {@code inventoryService.adjustDelta()} 落地库存账本.
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

    // ====================================================================
    // T5.1 状态机流转方法
    // ====================================================================

    /**
     * 提交调整单（DRAFT → PENDING_APPROVAL 或 APPROVED）.
     *
     * <p>按 {@link com.lumen.wms.WmsConstants#BIZ_ADJUST} 阈值评估金额 + 数量；
     * 任一维度超过阈值则路由到 {@code PENDING_APPROVAL}，否则直接进入 {@code APPROVED}.
     */
    WmsInventoryAdjust submit(Long noteId);

    /**
     * 审批调整单（PENDING_APPROVAL → APPROVED）.
     */
    WmsInventoryAdjust approve(Long noteId);

    /**
     * 驳回调整单（PENDING_APPROVAL → REJECTED）.
     *
     * <p>reason 会写入单据 {@code reject_reason} 字段（PR5-T5.2 新增）.
     */
    WmsInventoryAdjust reject(Long noteId, String reason);

    /**
     * 执行调整单（APPROVED → DONE，对每行调用
     * {@code inventoryService.adjustDelta()} 应用 deltaQty）.
     */
    WmsInventoryAdjust execute(Long noteId);

    /**
     * 取消调整单（DRAFT / PENDING_APPROVAL / APPROVED → CANCELLED）.
     *
     * <p>DONE / REJECTED / CANCELLED 状态拒绝取消.
     */
    WmsInventoryAdjust cancel(Long noteId);
}
