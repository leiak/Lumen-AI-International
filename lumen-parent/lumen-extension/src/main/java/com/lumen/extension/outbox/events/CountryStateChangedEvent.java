package com.lumen.extension.outbox.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    @JsonCreator
    public CountryStateChangedEvent(
            @JsonProperty("tenantId") Long tenantId,
            @JsonProperty("countryId") Long countryId,
            @JsonProperty("oldState") String oldState,
            @JsonProperty("newState") String newState,
            @JsonProperty("operatorId") Long operatorId) {
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