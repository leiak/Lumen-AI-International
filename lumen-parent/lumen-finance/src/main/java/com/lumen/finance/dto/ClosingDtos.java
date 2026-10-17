package com.lumen.finance.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

public class ClosingDtos {
    @Data
    public static class StartRequest {
        @NotBlank @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") private String period;
    }
    @Data
    public static class CloseRequest {
        @NotBlank @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") private String period;
    }
    @Data
    public static class ReopenRequest {
        @NotBlank @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") private String period;
        @NotBlank @Size(min = 10, max = 500) private String reason;
    }
}
