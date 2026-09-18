package com.lumen.extension.outbox.events;

import com.lumen.extension.outbox.DomainEvent;
import lombok.Getter;

import java.util.Objects;

@Getter
public class CountryEditRejectedEvent extends DomainEvent {
    private final Long tenantId;
    private final Long approvalId;
    private final Long countryId;
    private final Long approverId;
    private final String reason;

    public CountryEditRejectedEvent(Long tenantId, Long approvalId, Long countryId, Long approverId, String reason) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(countryId, "countryId");
        Objects.requireNonNull(approvalId, "approvalId");
        this.tenantId = tenantId;
        this.approvalId = approvalId;
        this.countryId = countryId;
        this.approverId = approverId;
        this.reason = reason;
    }

    @Override public String eventType() { return "country.edit_rejected"; }
    @Override public String aggregateType() { return "SysCountry"; }
    @Override public String aggregateId() { return countryId.toString(); }
    @Override public Long tenantId() { return tenantId; }
}