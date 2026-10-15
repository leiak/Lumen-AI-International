package com.lumen.cs;

public final class CsConstants {
    private CsConstants() {}

    // SLA event types (t_sla_event.event_type)
    public static final String SLA_EVENT_L1_RESPONSE_DUE   = "L1_RESPONSE_DUE";
    public static final String SLA_EVENT_L1_RESPONDED      = "L1_RESPONDED";
    public static final String SLA_EVENT_ESCALATED         = "ESCALATED";
    public static final String SLA_EVENT_RESOLVED          = "RESOLVED";
    public static final String SLA_EVENT_CANCELLED         = "CANCELLED";

    // Severity
    public static final String SEVERITY_HIGH   = "HIGH";
    public static final String SEVERITY_MEDIUM = "MEDIUM";
    public static final String SEVERITY_LOW    = "LOW";

    // Levels
    public static final String LEVEL_L1 = "L1";
    public static final String LEVEL_L2 = "L2";
    public static final String LEVEL_L3 = "L3";

    // WmsDeliveryException.type -> severity mapping (硬编码, see spec §3.5)
    public static final String TYPE_DAMAGE   = "DAMAGE";
    public static final String TYPE_OVERTIME = "OVERTIME";
    public static final String TYPE_REJECTED = "REJECTED";
    public static final String TYPE_ADDR_ERR = "ADDR_ERR";
}