package com.lumen.extension.outbox.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lumen.extension.outbox.DomainEvent;
import lombok.Getter;

import java.util.Objects;

@Getter
public class CountryEditSubmittedEvent extends DomainEvent {
    private final Long tenantId;
    private final Long approvalId;
    private final Long countryId;
    private final Long applicantId;

    @JsonCreator
    public CountryEditSubmittedEvent(
            @JsonProperty("tenantId") Long tenantId,
            @JsonProperty("approvalId") Long approvalId,
            @JsonProperty("countryId") Long countryId,
            @JsonProperty("applicantId") Long applicantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(countryId, "countryId");
        Objects.requireNonNull(approvalId, "approvalId");
        this.tenantId = tenantId;
        this.approvalId = approvalId;
        this.countryId = countryId;
        this.applicantId = applicantId;
    }

    @Override public String eventType() { return "country.edit_submitted"; }
    @Override public String aggregateType() { return "SysCountry"; }
    @Override public String aggregateId() { return countryId.toString(); }
    @Override public Long tenantId() { return tenantId; }
}