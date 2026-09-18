package com.lumen.extension.outbox.events;

import com.lumen.extension.outbox.DomainEvent;
import lombok.Getter;

import java.util.Objects;

@Getter
public class CountryStateChangedEvent extends DomainEvent {
    private final Long tenantId;
    private final Long countryId;
    private final String oldState;
    private final String newState;
    private final Long operatorId;

    public CountryStateChangedEvent(Long tenantId, Long countryId, String oldState, String newState, Long operatorId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(countryId, "countryId");
        this.tenantId = tenantId;
        this.countryId = countryId;
        this.oldState = oldState;
        this.newState = newState;
        this.operatorId = operatorId;
    }

    @Override public String eventType() { return "country.state_changed"; }
    @Override public String aggregateType() { return "SysCountry"; }
    @Override public String aggregateId() { return countryId.toString(); }
    @Override public Long tenantId() { return tenantId; }
}