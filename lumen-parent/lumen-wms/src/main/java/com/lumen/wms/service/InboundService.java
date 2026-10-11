package com.lumen.wms.service;

import com.lumen.common.api.PageResult;
import com.lumen.wms.dto.AddInboundLineRequest;
import com.lumen.wms.dto.CreateInboundNoteRequest;
import com.lumen.wms.dto.InboundLineVO;
import com.lumen.wms.dto.InboundNoteVO;
import com.lumen.wms.dto.UpdateInboundNoteRequest;
import com.lumen.wms.entity.WmsInboundLine;
import com.lumen.wms.entity.WmsInboundNote;

import java.math.BigDecimal;
import java.util.List;

/**
 * 入库单服务接口（T3.1 基础 CRUD + T3.4 状态机）.
 *
 * <p>状态机：DRAFT → PENDING_APPROVAL/RECEIVING → ... → PUTAWAY → DONE，
 * 任意非终态可取消为 CANCELLED。{@code submit} 会按阈值路由到 PENDING_APPROVAL 或 RECEIVING.
 */
public interface InboundService {

    /**
     * 分页查询入库单主表.
     *
     * @param keyword 模糊匹配 no，可空
     * @param status  按 status 精确过滤，可空
     */
    PageResult<WmsInboundNote> page(long pageNum, long pageSize, String keyword, String status);

    /**
     * 按主键查询主单.
     */
    WmsInboundNote getById(Long id);

    /**
     * 新建入库单（一次性带全部行项目）.
     */
    WmsInboundNote create(CreateInboundNoteRequest req);

    /**
     * 更新入库单主表；{@code req.lines()} 若非空则整量替换行项目.
     */
    WmsInboundNote update(Long id, UpdateInboundNoteRequest req);

    /**
     * 逻辑删除入库单（自动删除其下属行项目）.
     */
    void delete(Long id);

    /**
     * 追加单行到已有入库单.
     *
     * @param noteId  目标主单 ID
     * @param req     待追加行内容
     * @return 新行实体
     */
    WmsInboundLine addLine(Long noteId, AddInboundLineRequest req);

    /**
     * 删除指定行.
     */
    void removeLine(Long noteId, Long lineId);

    /**
     * 列出某主单下的全部行（按 line_no 升序）.
     */
    List<WmsInboundLine> listLines(Long noteId);

    /**
     * 实体转 VO（含行项目）.
     */
    InboundNoteVO toVO(WmsInboundNote note);

    /**
     * 行实体转 VO（批量转换用）.
     */
    List<InboundLineVO> lineVOs(List<WmsInboundLine> lines);

    // ====================================================================
    // T3.4 状态机流转方法
    // ====================================================================

    /**
     * 提交入库单（DRAFT → PENDING_APPROVAL 或 RECEIVING）.
     *
     * <p>按 {@link com.lumen.wms.WmsConstants#BIZ_INBOUND} 阈值评估金额 + 数量；
     * 任一维度超过阈值则路由到 {@code PENDING_APPROVAL}，否则直接进入 {@code RECEIVING}.
     * 当前实现只更新单据状态 — 与 {@code ApprovalService.submit()} 的集成
     * 待 {@code ApprovalRouter} 配置 {@code WMS_INBOUND} 后由后续 PR 接入.
     */
    WmsInboundNote submit(Long id);

    /**
     * 审批入库单（PENDING_APPROVAL → RECEIVING）.
     */
    void approve(Long id);

    /**
     * 取消入库单（任意非终态 → CANCELLED）.
     */
    void cancel(Long id);

    /**
     * 收货行项目（增量累加到 {@code receivedQty}）.
     *
     * <p>仅在 RECEIVING 状态可调用；收齐所有行后自动翻转为 PUTAWAY.
     */
    WmsInboundLine receiveLine(Long noteId, Long lineId, BigDecimal qty);

    /**
     * 上架行项目（增量累加到 {@code putawayQty} 并增加库存）.
     *
     * <p>仅在 PUTAWAY 状态可调用；上架完所有行后自动翻转为 DONE 并写入 completedAt.
     */
    WmsInboundLine putawayLine(Long noteId, Long lineId, BigDecimal qty, Long locationId);
}
