package com.lumen.wms;

public final class WmsConstants {
    private WmsConstants() {}

    // State machines
    public static final String SM_INBOUND    = "WMS_INBOUND";
    public static final String SM_OUTBOUND   = "WMS_OUTBOUND";
    public static final String SM_ADJUST     = "WMS_INV_ADJUST";
    public static final String SM_LOCATION   = "WMS_LOCATION";

    // Inbound statuses
    public static final String INB_DRAFT              = "DRAFT";
    public static final String INB_PENDING_APPROVAL   = "PENDING_APPROVAL";
    public static final String INB_RECEIVING          = "RECEIVING";
    public static final String INB_PUTAWAY            = "PUTAWAY";
    public static final String INB_DONE               = "DONE";
    public static final String INB_CANCELLED          = "CANCELLED";

    // Outbound statuses
    public static final String OUT_DRAFT              = "DRAFT";
    public static final String OUT_PENDING_APPROVAL   = "PENDING_APPROVAL";
    public static final String OUT_PICKING            = "PICKING";
    public static final String OUT_REVIEW             = "REVIEW";
    public static final String OUT_SHIPPED            = "SHIPPED";
    public static final String OUT_DONE               = "DONE";
    public static final String OUT_CANCELLED          = "CANCELLED";

    // Adjust statuses
    public static final String ADJ_DRAFT              = "DRAFT";
    public static final String ADJ_PENDING_APPROVAL   = "PENDING_APPROVAL";
    public static final String ADJ_APPROVED           = "APPROVED";
    public static final String ADJ_DONE               = "DONE";
    public static final String ADJ_REJECTED           = "REJECTED";
    public static final String ADJ_CANCELLED          = "CANCELLED";

    // Location statuses
    public static final String LOC_ACTIVE             = "ACTIVE";
    public static final String LOC_FROZEN             = "FROZEN";
    public static final String LOC_DISABLED           = "DISABLED";

    // Biz types for approval_record binding
    public static final String BIZ_INBOUND   = "WMS_INBOUND";
    public static final String BIZ_OUTBOUND  = "WMS_OUTBOUND";
    public static final String BIZ_ADJUST    = "WMS_INV_ADJUST";

    // Events published to t_event_outbox
    public static final String EVT_INB_STATE_CHANGED   = "WMS_INBOUND_STATE_CHANGED";
    public static final String EVT_OUT_STATE_CHANGED   = "WMS_OUTBOUND_STATE_CHANGED";
    public static final String EVT_ADJ_STATE_CHANGED   = "WMS_INV_ADJUST_STATE_CHANGED";
    public static final String EVT_LOC_STATE_CHANGED   = "WMS_LOCATION_STATE_CHANGED";
    public static final String EVT_INVENTORY_MOVED     = "WMS_INVENTORY_MOVED";
    public static final String EVT_THRESHOLD_EVAL      = "WMS_THRESHOLD_EVALUATED";
}
