package com.lumen.cs.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 业务时间 DTO 集合 (Iteration 4.2 PR2).
 *
 * <p>对应 {@code t_business_calendar}：每条记录是 (weekday, startTime, endTime)
 * 表示一个 tenant 在某星期几的工作时段；一天可有多段（午休拆分）.
 */
public final class CalendarDtos {
    private CalendarDtos() {}

    public record CreateRequest(
        @NotNull @Min(1) @Max(7) Integer weekday,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime
    ) {}

    public record UpdateRequest(
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime
    ) {}

    public record GetResponse(
        Long id,
        Long tenantId,
        Integer weekday,
        LocalTime startTime,
        LocalTime endTime,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {}

    public record ListRequest(
        Integer page,
        Integer size
    ) {}

    public record ListResponse(
        List<GetResponse> items,
        long total
    ) {}
}