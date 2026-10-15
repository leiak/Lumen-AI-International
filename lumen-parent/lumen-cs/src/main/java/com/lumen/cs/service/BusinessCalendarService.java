package com.lumen.cs.service;

import com.lumen.cs.dto.CalendarDtos.CreateRequest;
import com.lumen.cs.dto.CalendarDtos.GetResponse;
import com.lumen.cs.dto.CalendarDtos.ListRequest;
import com.lumen.cs.dto.CalendarDtos.ListResponse;
import com.lumen.cs.dto.CalendarDtos.UpdateRequest;

/**
 * 业务时间配置服务 (Iteration 4.2 PR2).
 *
 * <p>对应 {@code t_business_calendar}。约束：
 * <ul>
 *   <li>{@code weekday} 必须在 1..7（service 层校验）</li>
 *   <li>{@code end_time > start_time}（SQL CHECK 兜底，service 也校验）</li>
 *   <li>{@code (tenant_id, weekday, start_time, deleted)} UNIQUE KEY 防重复</li>
 * </ul>
 */
public interface BusinessCalendarService {

    GetResponse create(Long tenantId, CreateRequest req);

    GetResponse update(Long tenantId, Long id, UpdateRequest req);

    void delete(Long tenantId, Long id);

    GetResponse get(Long tenantId, Long id);

    ListResponse list(Long tenantId, ListRequest req);
}