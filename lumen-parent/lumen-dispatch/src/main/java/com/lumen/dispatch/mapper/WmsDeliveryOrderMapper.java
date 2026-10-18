package com.lumen.dispatch.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.dto.TrackQuery;
import com.lumen.dispatch.entity.WmsDeliveryOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 配送订单 Mapper.
 *
 * <p>Iteration 4.1 PR5 增加 {@link #aggregateByDateInTenant} 用于
 * 仪表盘车辆利用率聚合. SQL 走 {@code @Select} 注解，避免维护 XML mapper.
 */
@Mapper
public interface WmsDeliveryOrderMapper extends BaseMapper<WmsDeliveryOrder> {

    /**
     * 按日 + 车辆分组聚合：在指定租户 + 日期区间内，统计每个 (date, vehicle_id) 的
     * 派送单数、总重、总体积。状态过滤为 SIGNED 或 EXCEPTION（PR4 决定：EXCEPTION
     * 也算"已尝试派送"，但前端可单独高亮）。
     *
     * <p>结果行形如：
     * <pre>
     *   { date=2026-10-10, vehicle_id=200, dispatched_count=3, total_weight_kg=300.00, total_volume_m3=6.00 }
     * </pre>
     *
     * <p>tenant 维度由调用方传入 {@code TenantContext.require()}，避免 service 层
     * 重复拼装。{@code dateEnd} 取 {@code +1 天} 实现"inclusive end"语义。
     *
     * @param dateStart inclusive
     * @param dateEnd   inclusive（SQL 内部 {@code < dateEnd+1 day}）
     * @param tenantId  必填
     * @return 不为 null 的列表；无匹配时为空列表
     */
    @Select("""
            SELECT
                DATE(actual_depart_at)                                AS date,
                vehicle_id                                            AS vehicle_id,
                COUNT(*)                                              AS dispatched_count,
                COALESCE(SUM(total_weight_kg), 0)                     AS total_weight_kg,
                COALESCE(SUM(total_volume_m3), 0)                     AS total_volume_m3
            FROM wms_delivery_order
            WHERE tenant_id = #{tenantId}
              AND status IN ('SIGNED', 'EXCEPTION')
              AND actual_depart_at IS NOT NULL
              AND actual_depart_at >= #{dateStart}
              AND actual_depart_at <  DATE_ADD(#{dateEnd}, INTERVAL 1 DAY)
            GROUP BY DATE(actual_depart_at), vehicle_id
            ORDER BY date, vehicle_id
            """)
    List<Map<String, Object>> aggregateByDateInTenant(@Param("dateStart") LocalDate dateStart,
                                                      @Param("dateEnd") LocalDate dateEnd,
                                                      @Param("tenantId") Long tenantId);

    /**
     * 客户门户分页查询 (Iteration 4.5A.3 真实实现).
     *
     * <p>客户门户只能看属于自己 customer_id 的配送单. 走 (tenant_id, customer_id)
     * 索引 (V34). 可选按 {@code doNo} 模糊匹配 + {@code status} 精确过滤.
     *
     * <p>MyBatis-Plus {@code PaginationInnerInterceptor} 按 {@code IPage} 参数
     * 自动拼 LIMIT/OFFSET 并执行 count(*). {@code page} 必须位于参数列表前位
     * 才能被拦截器识别 (类型为 {@link IPage} 的子类).
     *
     * <p>{@code q.size()} / {@code q.page()} 由 TrackQuery 构造器保证 >=1.
     *
     * @return Page 的 records 是 {@link WmsDeliveryOrder} 列表; 由 service 层
     *         转 {@code Map<String,Object>} 暴露给 lumen-customer 的
     *         PortalTrackController (避免 lumen-dispatch → lumen-customer 依赖).
     */
    @Select("""
            <script>
            SELECT * FROM wms_delivery_order
            WHERE tenant_id   = #{tenantId}
              AND customer_id = #{customerId}
              AND deleted     = 0
              <if test='q.doNo != null and q.doNo != ""'>AND no LIKE CONCAT('%', #{q.doNo}, '%')</if>
              <if test='q.status != null and q.status != ""'>AND status = #{q.status}</if>
            ORDER BY created_at DESC, id DESC
            </script>
            """)
    IPage<WmsDeliveryOrder> pageForCustomer(Page<WmsDeliveryOrder> page,
                                            @Param("tenantId") Long tenantId,
                                            @Param("customerId") Long customerId,
                                            @Param("q") TrackQuery q);
}
