package com.lumen.rbac.service;

import com.lumen.rbac.entity.SysLoginLog;
import com.lumen.rbac.mapper.SysLoginLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAuditService {

    private final SysLoginLogMapper loginLogMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Long userId, String username, Long tenantId, String ip, String userAgent) {
        SysLoginLog log = new SysLoginLog();
        log.setUserId(userId);
        log.setUsername(username);
        log.setTenantId(tenantId);
        log.setIp(ip);
        log.setUserAgent(userAgent);
        log.setStatus(1);
        loginLogMapper.insert(log);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFail(String username, Long tenantId, String ip, String userAgent, String errorMsg) {
        SysLoginLog log = new SysLoginLog();
        log.setUsername(username);
        log.setTenantId(tenantId);
        log.setIp(ip);
        log.setUserAgent(userAgent);
        log.setStatus(0);
        log.setErrorMsg(errorMsg);
        loginLogMapper.insert(log);
    }
}
