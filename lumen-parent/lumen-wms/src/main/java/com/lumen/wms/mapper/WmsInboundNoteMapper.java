package com.lumen.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumen.wms.entity.WmsInboundLine;
import com.lumen.wms.entity.WmsInboundNote;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 入库单主表 / 行表 Mapper.
 *
 * <p>主表 CRUD 由 {@link BaseMapper} 自动覆盖；行表 CRUD 在此接口上以
 * {@code @Insert / @Select / @Delete} 形式声明 — 这样符合任务约定的"每个聚合
 * 一个 mapper 接口". 行表的批量 INSERT 由服务层循环拼接.
 *
 * <p>{@code tenant_id} / {@code deleted} 由
 * {@code TenantLineInnerInterceptor} / {@code @TableLogic} 自动注入，无需手动写入.
 */
@Mapper
public interface WmsInboundNoteMapper extends BaseMapper<WmsInboundNote> {

    /**
     * 查询某入库单的所有行（按 line_no 升序），排除已逻辑删除行.
     */
    @Select("SELECT id, tenant_id, note_id, line_no, item_id, qty, received_qty, "
            + "putaway_location_id, putaway_qty, created_at, updated_at, deleted, version "
            + "FROM wms_inbound_line "
            + "WHERE note_id = #{noteId} AND deleted = 0 "
            + "ORDER BY line_no ASC")
    List<WmsInboundLine> selectLinesByNoteId(@Param("noteId") Long noteId);

    /**
     * 插入一行；tenant_id 与 created_at / updated_at 由 MetaObjectHandler 自动填.
     */
    @Insert("INSERT INTO wms_inbound_line "
            + "(tenant_id, note_id, line_no, item_id, qty, received_qty, "
            + " putaway_location_id, putaway_qty, created_at, updated_at, deleted, version) "
            + "VALUES (#{tenantId}, #{noteId}, #{lineNo}, #{itemId}, #{qty}, "
            + " #{receivedQty}, #{putawayLocationId}, #{putawayQty}, NOW(), NOW(), 0, 1)")
    int insertLine(WmsInboundLine line);

    /**
     * 按主键删除指定行（物理删除；行无业务生命周期）.
     */
    @Delete("DELETE FROM wms_inbound_line WHERE id = #{id}")
    int deleteLineById(@Param("id") Long id);

    /**
     * 删除某入库单的所有行（创建/更新时全量替换前的清理）.
     */
    @Delete("DELETE FROM wms_inbound_line WHERE note_id = #{noteId}")
    int deleteLinesByNoteId(@Param("noteId") Long noteId);

    /**
     * 按 note_id + line_no 查询单行（用于添加单行场景下的冲突检查）.
     */
    @Select("SELECT id, tenant_id, note_id, line_no, item_id, qty, received_qty, "
            + "putaway_location_id, putaway_qty, created_at, updated_at, deleted, version "
            + "FROM wms_inbound_line WHERE note_id = #{noteId} AND line_no = #{lineNo} AND deleted = 0")
    WmsInboundLine selectLineByNoteIdAndLineNo(@Param("noteId") Long noteId,
                                              @Param("lineNo") Integer lineNo);

    /**
     * 按主键查询一行.
     */
    @Select("SELECT id, tenant_id, note_id, line_no, item_id, qty, received_qty, "
            + "putaway_location_id, putaway_qty, created_at, updated_at, deleted, version "
            + "FROM wms_inbound_line WHERE id = #{id}")
    WmsInboundLine selectLineById(@Param("id") Long id);

    /**
     * 更新行项目（T3.4 状态机 — 收货 / 上架累加 qty 时使用）.
     *
     * <p>{@code received_qty} / {@code putaway_qty} / {@code putaway_location_id}
     * 由调用方在服务层累加后再写入；乐观锁 {@code version} 自增.
     */
    @Update("UPDATE wms_inbound_line "
            + "SET received_qty = #{receivedQty}, "
            + "    putaway_qty = #{putawayQty}, "
            + "    putaway_location_id = #{putawayLocationId}, "
            + "    updated_at = NOW(), "
            + "    version = version + 1 "
            + "WHERE id = #{id} AND deleted = 0")
    int updateLineById(WmsInboundLine line);
}
