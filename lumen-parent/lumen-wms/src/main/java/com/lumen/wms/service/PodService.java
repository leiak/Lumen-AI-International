package com.lumen.wms.service;

import java.util.Map;

/**
 * 签收凭证 (Proof of Delivery) 服务 (Iteration 4.5A.2).
 *
 * <p>4.5A.2 占位: 当前 schema 无独立 POD 表, POD 数据由 {@code wms_delivery_order.signPhotoUrl}
 * 字段存储 (signed 状态写入). 后续 PR (4.5A.3) 拆出独立 {@code wms_delivery_pod} 表.
 *
 * <p>Customer portal overload: {@link #getForCustomer(Long, Long, Long)} 返回
 * {@code Map<String,Object>} 而非 lumen-customer DTO, 避免 Maven 循环依赖.
 */
public interface PodService {

    /**
     * 客户门户获取配送单的 POD 信息 (4.5A.2 占位).
     *
     * <p>4.5A.3 PR3 替换为真实实现: 按 tenantId + customerId 校验 doId 属于该客户,
     * 从 signPhotoUrl / 关联 stop / sign base64 (若有) 拼装.
     *
     * @param tenantId   租户 ID
     * @param customerId 当前激活的客户 ID
     * @param doId       配送单 ID
     * @return POD 视图 (Map: doId, podType, imageUrl, signatureBase64, signerName, signedAt);
     *         PR2 阶段返 {@code null}.
     */
    Map<String, Object> getForCustomer(Long tenantId, Long customerId, Long doId);
}