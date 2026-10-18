package com.lumen.finance.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

public class ReportDtos {

    public enum ReportType { SUMMARY, DETAIL, VARIANCE }

    @Data
    public static class ExportRequest {
        @NotBlank @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") private String period;
        @NotBlank @Size(min = 3, max = 3) private String currency;
        @NotNull private ReportType type;
    }
}