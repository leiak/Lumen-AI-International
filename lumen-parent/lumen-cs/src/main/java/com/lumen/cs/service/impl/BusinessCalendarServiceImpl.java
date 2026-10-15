package com.lumen.cs.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.cs.dto.CalendarDtos.CreateRequest;
import com.lumen.cs.dto.CalendarDtos.GetResponse;
import com.lumen.cs.dto.CalendarDtos.ListRequest;
import com.lumen.cs.dto.CalendarDtos.ListResponse;
import com.lumen.cs.dto.CalendarDtos.UpdateRequest;
import com.lumen.cs.entity.BusinessCalendar;
import com.lumen.cs.exception.CsErrorCode;
import com.lumen.cs.exception.CsException;
import com.lumen.cs.mapper.BusinessCalendarMapper;
import com.lumen.cs.service.BusinessCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 业务时间配置服务实现 (Iteration 4.2 PR2).
 *
 * <p>校验：weekday ∈ 1..7；end_time > start_time；
 * UNIQUE KEY (tenant_id, weekday, start_time, deleted) 防重复.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessCalendarServiceImpl implements BusinessCalendarService {

    private final BusinessCalendarMapper mapper;

    @Override
    @Transactional
    public GetResponse create(Long tenantId, CreateRequest req) {
        validateWeekday(req.weekday());
        validateRange(req.startTime(), req.endTime());
        BusinessCalendar row = new BusinessCalendar();
        row.setTenantId(tenantId);
        row.setWeekday(req.weekday());
        row.setStartTime(req.startTime());
        row.setEndTime(req.endTime());
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException e) {
            log.warn("duplicate business calendar tenant={} weekday={} start={}",
                tenantId, req.weekday(), req.startTime());
            throw new CsException(CsErrorCode.DUPLICATE_BUSINESS_CALENDAR,
                "Business calendar entry already exists for this weekday + start time");
        }
        return toDto(row);
    }

    @Override
    @Transactional
    public GetResponse update(Long tenantId, Long id, UpdateRequest req) {
        validateRange(req.startTime(), req.endTime());
        BusinessCalendar row = mustFind(tenantId, id);
        row.setStartTime(req.startTime());
        row.setEndTime(req.endTime());
        mapper.updateById(row);
        return toDto(mapper.selectById(row.getId()));
    }

    @Override
    @Transactional
    public void delete(Long tenantId, Long id) {
        BusinessCalendar row = mustFind(tenantId, id);
        mapper.deleteById(row.getId());
    }

    @Override
    public GetResponse get(Long tenantId, Long id) {
        return toDto(mustFind(tenantId, id));
    }

    @Override
    public ListResponse list(Long tenantId, ListRequest req) {
        int pageNum = req == null || req.page() == null || req.page() < 1 ? 1 : req.page();
        int pageSize = req == null || req.size() == null || req.size() < 1 ? 200 : Math.min(req.size(), 200);
        Page<BusinessCalendar> page = Page.of(pageNum, pageSize);
        Page<BusinessCalendar> result = mapper.selectPage(page,
            new LambdaQueryWrapper<BusinessCalendar>()
                .eq(BusinessCalendar::getTenantId, tenantId)
                .orderByAsc(BusinessCalendar::getWeekday, BusinessCalendar::getStartTime));
        List<GetResponse> items = result.getRecords().stream().map(this::toDto).toList();
        return new ListResponse(items, result.getTotal());
    }

    private BusinessCalendar mustFind(Long tenantId, Long id) {
        BusinessCalendar row = mapper.selectById(id);
        if (row == null || !row.getTenantId().equals(tenantId)) {
            throw new CsException(CsErrorCode.BUSINESS_CALENDAR_NOT_FOUND);
        }
        return row;
    }

    private static void validateWeekday(Integer weekday) {
        if (weekday == null || weekday < 1 || weekday > 7) {
            throw new CsException(CsErrorCode.INVALID_WEEKDAY);
        }
    }

    private static void validateRange(java.time.LocalTime start, java.time.LocalTime end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new CsException(CsErrorCode.INVALID_TIME_RANGE, "end_time must be > start_time");
        }
    }

    private GetResponse toDto(BusinessCalendar row) {
        return new GetResponse(
            row.getId(), row.getTenantId(),
            row.getWeekday(), row.getStartTime(), row.getEndTime(),
            row.getCreatedAt(), row.getUpdatedAt()
        );
    }
}