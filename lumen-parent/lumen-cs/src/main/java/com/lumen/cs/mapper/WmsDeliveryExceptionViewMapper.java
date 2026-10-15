package com.lumen.cs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumen.cs.entity.WmsDeliveryExceptionView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 配送异常工单 只读视图 Mapper (Iteration 4.2 PR4).
 *
 * <p>视图实体 {@link WmsDeliveryExceptionView} 映射 lumen-dispatch 拥有的
 * {@code wms_delivery_exception} 表 — lumen-cs 不写该表, 这里仅供
 * SLA 升级扫描器查询 OPEN / INVESTIGATING 工单.
 *
 * <p>{@link #selectOpenByTenant} 走 {@code @Select} 注解, 显式过滤:
 * <ul>
 *   <li>{@code deleted = 0} — 软删除行跳过</li>
 *   <li>{@code status IN ('OPEN', 'INVESTIGATING')} — 只处理未结束工单</li>
 *   <li>{@code tenant_id = #{tenantId}} — 多租户隔离</li>
 * </ul>
 */
@Mapper
public interface WmsDeliveryExceptionViewMapper extends BaseMapper<WmsDeliveryExceptionView> {

    @Select("""
            SELECT * FROM wms_delivery_exception
            WHERE deleted = 0
              AND status IN ('OPEN', 'INVESTIGATING')
              AND tenant_id = #{tenantId}
            ORDER BY created_at
            """)
    List<WmsDeliveryExceptionView> selectOpenByTenant(Long tenantId);
}
