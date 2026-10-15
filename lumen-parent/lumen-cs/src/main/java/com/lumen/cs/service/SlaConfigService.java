package com.lumen.cs.service;

import com.lumen.cs.dto.SlaConfigDtos.CreateRequest;
import com.lumen.cs.dto.SlaConfigDtos.GetResponse;
import com.lumen.cs.dto.SlaConfigDtos.ListRequest;
import com.lumen.cs.dto.SlaConfigDtos.ListResponse;
import com.lumen.cs.dto.SlaConfigDtos.UpdateRequest;

/**
 * SLA 配置服务 (Iteration 4.2 PR2).
 *
 * <p>对应 {@code t_sla_config}。约束：
 * <ul>
 *   <li>{@code resolve_minutes >= response_minutes}（service 层校验）</li>
 *   <li>{@code (tenant_id, severity, level, deleted)} UNIQUE KEY 防并发重复</li>
 * </ul>
 */
public interface SlaConfigService {

    GetResponse create(Long tenantId, CreateRequest req);

    GetResponse update(Long tenantId, Long id, UpdateRequest req);

    void delete(Long tenantId, Long id);

    GetResponse get(Long tenantId, Long id);

    ListResponse list(Long tenantId, ListRequest req);
}