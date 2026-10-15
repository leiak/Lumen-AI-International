package com.lumen.cs.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * SLA 配置 DTO 集合 (Iteration 4.2 PR2).
 *
 * <p>Java 17 records + bean validation。{@link CreateRequest} / {@link UpdateRequest}
 * 是 controller 入参；{@link GetResponse} 是出参；{@link ListRequest} / {@link ListResponse}
 * 是分页入参 / 出参.
 */
public final class SlaConfigDtos {
    private SlaConfigDtos() {}

    public record CreateRequest(
        @NotBlank String severity,
        @NotBlank String level,
        @NotNull @Min(1) Integer responseMinutes,
        @NotNull @Min(1) Integer resolveMinutes,
        String escalateToLevel
    ) {}

    public record UpdateRequest(
        @NotNull @Min(1) Integer responseMinutes,
        @NotNull @Min(1) Integer resolveMinutes,
        String escalateToLevel
    ) {}

    public record GetResponse(
        Long id,
        Long tenantId,
        String severity,
        String level,
        Integer responseMinutes,
        Integer resolveMinutes,
        String escalateToLevel,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {}

    public record ListRequest(
        String severity,
        String level,
        Integer page,
        Integer size
    ) {}

    public record ListResponse(
        List<GetResponse> items,
        long total
    ) {}
}