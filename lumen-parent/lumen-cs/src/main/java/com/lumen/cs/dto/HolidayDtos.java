package com.lumen.cs.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 节假日 DTO 集合 (Iteration 4.2 PR2).
 *
 * <p>对应 {@code t_holiday}：每条记录是 (tenant_id, holiday_date, description);
 * {@code (tenant_id, holiday_date, deleted)} 唯一键防重复 — service 捕获
 * {@link org.springframework.dao.DuplicateKeyException} 后翻译为
 * {@link com.lumen.cs.exception.CsErrorCode#DUPLICATE_HOLIDAY}.
 */
public final class HolidayDtos {
    private HolidayDtos() {}

    public record CreateRequest(
        @NotNull LocalDate holidayDate,
        String description
    ) {}

    public record UpdateRequest(
        String description
    ) {}

    public record GetResponse(
        Long id,
        Long tenantId,
        LocalDate holidayDate,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {}

    public record ListResponse(
        List<GetResponse> items,
        long total
    ) {}
}