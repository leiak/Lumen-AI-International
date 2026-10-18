package com.lumen.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumen.wms.entity.Pod;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 签收凭证 Mapper (Iteration 4.5A.3 T3.2).
 *
 * <p>Base CRUD 由 {@link BaseMapper} 自动覆盖. {@link #selectByDoId} 是 4.5A.3
 * 客户门户场景的实际查询入口 — 按 {@code do_id} 唯一取一条活跃 ({@code deleted=0})
 * POD 行 (V35 唯一键 {@code uk_wms_pod_do (do_id, deleted)} 保证不重复).
 *
 * <p>{@code tenant_id} / {@code deleted} 由
 * {@code TenantLineInnerInterceptor} / {@code @TableLogic} 自动注入, 无需手动写入.
 */
@Mapper
public interface PodMapper extends BaseMapper<Pod> {

    /**
     * 按配送单 ID 查询活跃 POD ({@code deleted=0}).
     *
     * <p>客户门户 PortalTrackController 调用约定: 先调 {@code DeliveryOrderService.
     * getDetailForCustomer} 做 cross-tenant / cross-customer 404 校验, 通过后再
     * 调 {@code PodService.getForCustomer} → 本方法. 此处不再做 ownership 检查
     * (薄门面设计, 控制器层确保).
     *
     * @param doId 配送单 ID
     * @return 该 DO 的活跃 POD; 无则 null
     */
    @Select("SELECT id, tenant_id, do_id, pod_type, image_url, signature_base64, "
            + "signer_name, signed_at, created_at, updated_at, deleted, version "
            + "FROM wms_delivery_pod "
            + "WHERE do_id = #{doId} AND deleted = 0 "
            + "LIMIT 1")
    Pod selectByDoId(@Param("doId") Long doId);
}
