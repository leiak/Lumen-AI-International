package com.lumen.finance.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class PaymentRecordDtos {

    @Data
    public static class CreateRequest {
        @NotBlank @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") private String period;
        @NotBlank private String direction;
        @NotNull private Long counterpartyId;
        @NotBlank private String counterpartyType;
        @NotBlank @Size(min = 3, max = 3) private String currency;
        @NotNull @DecimalMin("0.01") private BigDecimal amount;
        @NotNull private LocalDate paymentDate;
        private String sourceRef;
        private String remark;
    }

    @Data
    public static class ImportResult {
        private int totalRows;
        private int successCount;
        private int skippedCount;
        private List<String> errors;
    }
}