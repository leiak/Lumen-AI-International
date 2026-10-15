package com.lumen.cs.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.cs.dto.HolidayDtos.CreateRequest;
import com.lumen.cs.dto.HolidayDtos.GetResponse;
import com.lumen.cs.dto.HolidayDtos.ListRequest;
import com.lumen.cs.dto.HolidayDtos.ListResponse;
import com.lumen.cs.dto.HolidayDtos.UpdateRequest;
import com.lumen.cs.entity.Holiday;
import com.lumen.cs.exception.CsErrorCode;
import com.lumen.cs.exception.CsException;
import com.lumen.cs.mapper.HolidayMapper;
import com.lumen.cs.service.HolidayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 节假日服务实现 (Iteration 4.2 PR2).
 *
 * <p>{@code (tenant_id, holiday_date, deleted)} UNIQUE KEY 防并发重复 — service
 * 捕获 {@link DuplicateKeyException} 后翻译为 {@link CsErrorCode#DUPLICATE_HOLIDAY}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HolidayServiceImpl implements HolidayService {

    private final HolidayMapper mapper;

    @Override
    @Transactional
    public GetResponse create(Long tenantId, CreateRequest req) {
        Holiday row = new Holiday();
        row.setTenantId(tenantId);
        row.setHolidayDate(req.holidayDate());
        row.setDescription(req.description());
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException e) {
            log.warn("holiday duplicate tenant={} date={}", tenantId, req.holidayDate());
            throw new CsException(CsErrorCode.DUPLICATE_HOLIDAY);
        }
        return toDto(row);
    }

    @Override
    @Transactional
    public GetResponse update(Long tenantId, Long id, UpdateRequest req) {
        Holiday row = mustFind(tenantId, id);
        row.setDescription(req.description());
        mapper.updateById(row);
        return toDto(mapper.selectById(row.getId()));
    }

    @Override
    @Transactional
    public void delete(Long tenantId, Long id) {
        Holiday row = mustFind(tenantId, id);
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
        Page<Holiday> page = Page.of(pageNum, pageSize);
        Page<Holiday> result = mapper.selectPage(page,
            new LambdaQueryWrapper<Holiday>()
                .eq(Holiday::getTenantId, tenantId)
                .orderByAsc(Holiday::getHolidayDate));
        List<GetResponse> items = result.getRecords().stream().map(this::toDto).toList();
        return new ListResponse(items, result.getTotal());
    }

    @Override
    public boolean isHoliday(Long tenantId, LocalDate date) {
        return mapper.selectCount(new LambdaQueryWrapper<Holiday>()
            .eq(Holiday::getTenantId, tenantId)
            .eq(Holiday::getHolidayDate, date)) > 0;
    }

    private Holiday mustFind(Long tenantId, Long id) {
        Holiday row = mapper.selectById(id);
        if (row == null || !row.getTenantId().equals(tenantId)) {
            throw new CsException(CsErrorCode.HOLIDAY_NOT_FOUND);
        }
        return row;
    }

    private GetResponse toDto(Holiday row) {
        return new GetResponse(
            row.getId(), row.getTenantId(),
            row.getHolidayDate(), row.getDescription(),
            row.getCreatedAt(), row.getUpdatedAt()
        );
    }
}