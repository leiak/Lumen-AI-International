package com.lumen.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumen.wms.entity.WmsInventoryAdjust;
import com.lumen.wms.entity.WmsInventoryAdjustLine;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 库存调整单主表 / 行表 Mapper.
 *
 * <p>主表 CRUD 由 {@link BaseMapper} 自动覆盖；行表 CRUD 以注解方式声明，
 * 与 {@link WmsInboundNoteMapper} 保持一致的风格.
 */
@Mapper
public interface WmsInventoryAdjustMapper extends BaseMapper<WmsInventoryAdjust> {

    /**
     * 查询某调整单的所有行（按 line_no 升序），排除已逻辑删除行.
     */
    @Select("SELECT id, tenant_id, adjust_id, line_no, item_id, location_id, "
            + "delta_qty, reason_detail, created_at, updated_at, deleted, version "
            + "FROM wms_inventory_adjust_line "
            + "WHERE adjust_id = #{adjustId} AND deleted = 0 "
            + "ORDER BY line_no ASC")
    List<WmsInventoryAdjustLine> selectLinesByAdjustId(@Param("adjustId") Long adjustId);

    @Insert("INSERT INTO wms_inventory_adjust_line "
            + "(tenant_id, adjust_id, line_no, item_id, location_id, "
            + " delta_qty, reason_detail, created_at, updated_at, deleted, version) "
            + "VALUES (#{tenantId}, #{adjustId}, #{lineNo}, #{itemId}, #{locationId}, "
            + " #{deltaQty}, #{reasonDetail}, NOW(), NOW(), 0, 1)")
    int insertLine(WmsInventoryAdjustLine line);

    @Delete("DELETE FROM wms_inventory_adjust_line WHERE id = #{id}")
    int deleteLineById(@Param("id") Long id);

    @Delete("DELETE FROM wms_inventory_adjust_line WHERE adjust_id = #{adjustId}")
    int deleteLinesByAdjustId(@Param("adjustId") Long adjustId);

    @Select("SELECT id, tenant_id, adjust_id, line_no, item_id, location_id, "
            + "delta_qty, reason_detail, created_at, updated_at, deleted, version "
            + "FROM wms_inventory_adjust_line "
            + "WHERE adjust_id = #{adjustId} AND line_no = #{lineNo} AND deleted = 0")
    WmsInventoryAdjustLine selectLineByAdjustIdAndLineNo(@Param("adjustId") Long adjustId,
                                                         @Param("lineNo") Integer lineNo);

    @Select("SELECT id, tenant_id, adjust_id, line_no, item_id, location_id, "
            + "delta_qty, reason_detail, created_at, updated_at, deleted, version "
            + "FROM wms_inventory_adjust_line WHERE id = #{id}")
    WmsInventoryAdjustLine selectLineById(@Param("id") Long id);
}
