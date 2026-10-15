package com.lumen.cs.service;

import com.lumen.cs.dto.HolidayDtos.CreateRequest;
import com.lumen.cs.dto.HolidayDtos.GetResponse;
import com.lumen.cs.dto.HolidayDtos.ListResponse;
import com.lumen.cs.dto.HolidayDtos.UpdateRequest;

import java.time.LocalDate;

/**
 * 节假日配置服务 (Iteration 4.2 PR2).
 *
 * <p>对应 {@code t_holiday}。约束：
 * <ul>
 *   <li>{@code (tenant_id, holiday_date, deleted)} UNIQUE KEY 防并发重复</li>
 * </ul>
 */
public interface HolidayService {

    GetResponse create(Long tenantId, CreateRequest req);

    GetResponse update(Long tenantId, Long id, UpdateRequest req);

    void delete(Long tenantId, Long id);

    GetResponse get(Long tenantId, Long id);

    ListResponse list(Long tenantId);

    /** Convenience: 检查指定日期是否为该 tenant 的节假日（用于 SlaCalculator 集成）。 */
    boolean isHoliday(Long tenantId, LocalDate date);
}