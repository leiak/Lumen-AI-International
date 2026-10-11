package com.lumen.wms.service;

import com.lumen.common.api.PageResult;
import com.lumen.wms.dto.AddOutboundLineRequest;
import com.lumen.wms.dto.CreateOutboundNoteRequest;
import com.lumen.wms.dto.OutboundLineVO;
import com.lumen.wms.dto.OutboundNoteVO;
import com.lumen.wms.dto.UpdateOutboundNoteRequest;
import com.lumen.wms.entity.WmsOutboundLine;
import com.lumen.wms.entity.WmsOutboundNote;

import java.util.List;

/**
 * 出库单服务接口（T4.1 基础 CRUD）.
 *
 * <p>本接口只覆盖 CRUD 与行维护；状态机（DRAFT → PENDING_APPROVAL → PICKING →
 * REVIEW → SHIPPED → DONE / CANCELLED）将在 T4.2 引入.
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
}
