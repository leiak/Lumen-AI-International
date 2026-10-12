package com.lumen.dispatch;

/**
 * Dispatch 模块常量.
 *
 * <p>状态码以字符串常量集中维护，与 {@link #SM_DISPATCH_ORDER} 等状态机编码
 * 保持一致。状态机行种子见 V20 migration {@code t_state_transition}.
 */
public final class DispatchConstants {
    private DispatchConstants() {}

    // =============================================================
    // State machines — 与 t_state_transition.state_machine_code 列值一致
    // =============================================================
    public static final String SM_DISPATCH_ORDER        = "DISPATCH_ORDER";
    public static final String SM_VEHICLE               = "VEHICLE";
    public static final String SM_EXCEPTION_WORK_ORDER  = "EXCEPTION_WORK_ORDER";

    // =============================================================
    // DISPATCH_ORDER (配送单) statuses
    // =============================================================
    public static final String DO_PENDING_DISPATCH = "PENDING_DISPATCH";
    public static final String DO_DISPATCHED       = "DISPATCHED";
    public static final String DO_LOADED           = "LOADED";
    public static final String DO_ENROUTE          = "ENROUTE";
    public static final String DO_ARRIVED          = "ARRIVED";
    public static final String DO_SIGNED           = "SIGNED";
    public static final String DO_CANCELLED        = "CANCELLED";
    public static final String DO_EXCEPTION        = "EXCEPTION";

    // =============================================================
    // VEHICLE (车辆) statuses
    // =============================================================
    public static final String VEH_IDLE       = "IDLE";
    public static final String VEH_DISPATCHED = "DISPATCHED";
    public static final String VEH_LOADING    = "LOADING";
    public static final String VEH_ENROUTE    = "ENROUTE";
    public static final String VEH_RETURNING  = "RETURNING";
    public static final String VEH_OUT        = "OUT";

    // =============================================================
    // EXCEPTION_WORK_ORDER (异常工单) statuses
    // =============================================================
    public static final String EX_OPEN          = "OPEN";
    public static final String EX_INVESTIGATING = "INVESTIGATING";
    public static final String EX_RESOLVED      = "RESOLVED";
    public static final String EX_CLOSED        = "CLOSED";
    public static final String EX_CANCELLED     = "CANCELLED";

    // =============================================================
    // 通用主数据 (driver / carrier) 启停状态 — DB CHECK 约束 ACTIVE/SUSPENDED
    // =============================================================
    public static final String STATUS_ACTIVE    = "ACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";

    // =============================================================
    // Biz types / business keys
    // =============================================================
    /** Biz type for outbox / audit binding. */
    public static final String BIZ_DISPATCH = "DISPATCH";

    // =============================================================
    // Config defaults
    // =============================================================
    /** {@code dlv.max_stop_per_vehicle} 默认值 (per spec §5.2). */
    public static final int DEFAULT_MAX_STOP_PER_VEHICLE = 30;

    // =============================================================
    // resolveException actions / strategies (Iteration 4.1.x PR1)
    // =============================================================
    /** DISPATCH_ORDER 状态机 action: EXCEPTION → DISPATCHED. 对应 V23 seed reDispatch. */
    public static final String DO_REDISPATCH_ACTION = "reDispatch";
    /** DISPATCH_ORDER 状态机 action: EXCEPTION → CANCELLED. 对应 V23 seed exceptionCancel. */
    public static final String DO_EXCEPTION_CANCEL_ACTION = "exceptionCancel";
    /** 处置策略: 重新派车 (走 reDispatch). */
    public static final String STRATEGY_REDISPATCH = "RE_DISPATCH";
    /** 处置策略: 直接取消 (走 exceptionCancel). */
    public static final String STRATEGY_EXCEPTION_CANCEL = "CANCEL";
}
