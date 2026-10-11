package com.lumen.wms.service;

import com.lumen.common.api.PageResult;
import com.lumen.wms.dto.AddOutboundLineRequest;
import com.lumen.wms.dto.CreateOutboundNoteRequest;
import com.lumen.wms.dto.OutboundLineVO;
import com.lumen.wms.dto.OutboundNoteVO;
import com.lumen.wms.dto.UpdateOutboundNoteRequest;
import com.lumen.wms.entity.WmsOutboundLine;
import com.lumen.wms.entity.WmsOutboundNote;

import java.math.BigDecimal;
import java.util.List;

/**
 * 出库单服务接口（T4.1 基础 CRUD + T4.2 状态机）.
 *
 * <p>状态机：DRAFT → PENDING_APPROVAL/PICKING → REVIEW → SHIPPED → DONE，
 * 任意非终态可取消为 CANCELLED。{@code submit} 会按阈值路由到 PENDING_APPROVAL 或 PICKING，
 * 并对每行执行库存锁预留（{@code inventoryService.lockQty()}）.
 */
public interface OutboundService {

    /**
     * 分页查询出库单主表.
     *
     * @param keyword 模糊匹配 no，可空
     * @param status  按 status 精确过滤，可空
     */
    PageResult<WmsOutboundNote> page(long pageNum, long pageSize, String keyword, String status);

    /**
     * 按主键查询主单.
     */
    WmsOutboundNote getById(Long id);

    /**
     * 新建出库单（一次性带全部行项目）.
     */
    WmsOutboundNote create(CreateOutboundNoteRequest req);

    /**
     * 更新出库单主表；{@code req.lines} 若非空则整量替换行项目.
     *
     * <p>主单的 {@code no / customerId / warehouseId / status} 等不可变字段不在
     * 本接口的更新范围内.
     */
    WmsOutboundNote update(Long id, UpdateOutboundNoteRequest req);

    /**
     * 逻辑删除出库单（自动删除其下属行项目）.
     */
    void delete(Long id);

    /**
     * 追加单行到已有出库单.
     *
     * @param noteId 目标主单 ID
     * @param req    待追加行内容（必须指定 sourceLocationId）
     * @return 新行实体
     */
    WmsOutboundLine addLine(Long noteId, AddOutboundLineRequest req);

    /**
     * 删除指定行.
     */
    void removeLine(Long noteId, Long lineId);

    /**
     * 列出某主单下的全部行（按 line_no 升序）.
     */
    List<WmsOutboundLine> listLines(Long noteId);

    /**
     * 实体转 VO；本方法不自动加载行项目 — {@code lines} 字段保持为 null.
     *
     * <p>调用方如需行项目可显式：{@code vo.setLines(lineVOs(...))}.
     */
    OutboundNoteVO toVO(WmsOutboundNote note);

    /**
     * 行实体转 VO（批量转换用）.
     */
    List<OutboundLineVO> lineVOs(List<WmsOutboundLine> lines);

    // ====================================================================
    // T4.2 状态机流转方法
    // ====================================================================

    /**
     * 提交出库单（DRAFT → PENDING_APPROVAL 或 PICKING）.
     *
     * <p>按 {@link com.lumen.wms.WmsConstants#BIZ_OUTBOUND} 阈值评估金额 + 数量；
     * 任一维度超过阈值则路由到 {@code PENDING_APPROVAL}，否则直接进入 {@code PICKING}.
     * 同时对每行调用 {@code inventoryService.lockQty()} 锁库存 — 库存不足时
     * 抛 {@code InsufficientInventoryException}.
     */
    WmsOutboundNote submit(Long id);

    /**
     * 审批出库单（PENDING_APPROVAL → PICKING）.
     */
    void approve(Long id);

    /**
     * 拣货行项目（增量累加到 {@code pickedQty} 并释放对应锁库存）.
     *
     * <p>仅在 PICKING 状态可调用；拣齐所有行后自动翻转为 REVIEW.
     */
    WmsOutboundLine pickLine(Long noteId, Long lineId, BigDecimal qty);

    /**
     * 发货行项目（REVIEW → SHIPPED + 写入 carrier / trackingNo）.
     *
     * <p>仅在 REVIEW 状态可调用；写入承运商与运单号后翻转为 SHIPPED.
     * 库存锁在 {@link #complete(Long)} 中 flush.
     */
    WmsOutboundNote ship(Long noteId, String carrier, String trackingNo);

    /**
     * 完成出库单（SHIPPED → DONE + flush 所有锁库存 + 设置 shippedAt）.
     *
     * <p>仅在 SHIPPED 状态可调用；遍历每行执行 {@code inventoryService.flushLock()}
     * 把 {@code locked_qty} 转为消耗，最终翻转为 DONE.
     */
    WmsOutboundNote complete(Long id);

    /**
     * 取消出库单（任意非终态 → CANCELLED + 释放未拣完的库存锁）.
     *
     * <p>DONE / CANCELLED 状态拒绝取消；PICKING / REVIEW / SHIPPED 状态下
     * 会对未拣完的行释放对应的库存锁.
     */
    WmsOutboundNote cancel(Long id);
}
