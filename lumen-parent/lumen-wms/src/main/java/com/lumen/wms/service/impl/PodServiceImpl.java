package com.lumen.wms.service.impl;

import com.lumen.wms.service.PodService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 签收凭证服务实现 (Iteration 4.5A.2 占位).
 *
 * <p>4.5A.2 当前实现: 仅返 {@code null}, 不连数据库.
 * 4.5A.3 PR3 替换为真实实现 — 按 tenantId + customerId 校验 doId,
 * 从 signPhotoUrl / 关联 stop / 签名 base64 拼装视图.
 */
@Slf4j
@Service
public class PodServiceImpl implements PodService {

    @Override
    public Map<String, Object> getForCustomer(Long tenantId, Long customerId, Long doId) {
        log.debug("PodService.getForCustomer stub: tenantId={} customerId={} doId={}",
                tenantId, customerId, doId);
        return null;  // PR3 替换
    }
}