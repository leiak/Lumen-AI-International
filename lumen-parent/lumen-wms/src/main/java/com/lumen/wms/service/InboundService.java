package com.lumen.wms.service;

import com.lumen.common.api.PageResult;
import com.lumen.wms.dto.AddInboundLineRequest;
import com.lumen.wms.dto.CreateInboundNoteRequest;
import com.lumen.wms.dto.InboundLineVO;
import com.lumen.wms.dto.InboundNoteVO;
import com.lumen.wms.dto.UpdateInboundNoteRequest;
import com.lumen.wms.entity.WmsInboundLine;
import com.lumen.wms.entity.WmsInboundNote;

import java.util.List;

/**
 * 入库单服务接口（T3.1: 基础 CRUD + 持久化）.
 *
 * <p>状态机迁移（草稿 → 待审批 → 收货中 → 上架中 → 已完成）在 T3.4 中接入；
 * 本接口仅提供主单 + 行级别的读写.
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
}
