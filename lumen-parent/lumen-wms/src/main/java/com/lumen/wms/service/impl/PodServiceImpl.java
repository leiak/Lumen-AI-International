package com.lumen.wms.service.impl;

import com.lumen.wms.entity.Pod;
import com.lumen.wms.mapper.PodMapper;
import com.lumen.wms.service.PodService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 签收凭证服务实现 (Iteration 4.5A.3 T3.2 — 真实实现).
 *
 * <p>数据源: V35 新表 {@code wms_delivery_pod} (按 {@code do_id} 唯一取一条).
 * 此前 4.5A.2 占位阶段仅返 {@code null}; 现替换为查 POD 表后组装客户门户视图.
 *
 * <p>设计: 薄门面. 跨租户 / 跨客户的 ownership 校验由上层的
 * {@code DeliveryOrderService.getDetailForCustomer} 统一负责 (404 抛
 * {@code DeliveryOrderNotFoundException}). {@code PortalTrackController} 调用
 * 顺序保证: 先调 DO 详情 (已校验), 再调本方法. 因此 {@code tenantId} /
 * {@code customerId} 参数在 impl 中未使用 — 保留接口签名以备未来 defense-in-depth
 * 升级 (T3.1 复盘建议) 或他处复用.
 */
@Slf4j
@Service
public class PodServiceImpl implements PodService {

    private final PodMapper podMapper;

    public PodServiceImpl(PodMapper podMapper) {
        this.podMapper = podMapper;
    }

    @Override
    public Map<String, Object> getForCustomer(Long tenantId, Long customerId, Long doId) {
        log.debug("PodService.getForCustomer tenantId={} customerId={} doId={}",
                tenantId, customerId, doId);

        Pod p = podMapper.selectByDoId(doId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("doId", doId);

        if (p == null) {
            // 未签收 / 未上传凭证: 显式 NONE 桩, 字段空串 (非 null) — 客户端无需空判定
            result.put("podType", "NONE");
            result.put("imageUrl", "");
            result.put("signatureBase64", "");
            result.put("signerName", "");
            result.put("signedAt", "");
            return result;
        }

        result.put("podType", p.getPodType());
        result.put("imageUrl", p.getImageUrl());
        result.put("signatureBase64", p.getSignatureBase64());
        result.put("signerName", p.getSignerName());
        result.put("signedAt", p.getSignedAt());
        return result;
    }
}
