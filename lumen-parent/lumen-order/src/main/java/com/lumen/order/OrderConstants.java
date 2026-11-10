package com.lumen.order;

import java.util.List;

/**
 * 货运委托单的枚举取值与状态机常量 (Iteration 4.6).
 *
 * <p><b>状态用 ASCII 而非中文</b> —— 全仓代码一律 ASCII ({@code wms_delivery_order.status='PENDING_DISPATCH'}),
 * 中文展示名由前端本地 label map 提供 (照 {@code FinanceAdjustmentList} / {@code StatusTag} 的惯例),
 * 不塞进 {@code t_state_transition.from_state}, 也不查 sys_dict。
 *
 * <p><b>状态机是表驱动的</b> —— 真正的 from→to 合法性由 {@code t_state_transition} (seed 在 V42)
 * + {@code StateMachineEngine} 判定。本类里的 {@link #TRANSITIONS} 是**同一张表的 Java 侧镜像**,
 * 用途只有两个: (1) bootstrap 的 {@code OrderSchemaIT} 把 DB 里的 {@code t_state_transition}
 * 与本镜像逐行比对 (DB ↔ Java 两侧不再各说各话); (2) service 层做守卫时快速取动作名。
 * 它**不是**第二份状态机定义 —— service 判合法性必须走 {@code StateMachineEngine}。
 *
 * <p><b>枚举取值的暴露口径统一如下</b> —— 枚举取值一律以 {@code List} 形式暴露
 * (service 校验的唯一权威); 标量常量只在**某个具体值需要在代码里被指名引用**时才定义。
 */
public final class OrderConstants {

    private OrderConstants() {}

    // ------------------------------------------------------------------ 状态

    public static final String STATUS_DRAFT           = "DRAFT";
    public static final String STATUS_PENDING_CONFIRM = "PENDING_CONFIRM";
    public static final String STATUS_CONFIRMED       = "CONFIRMED";
    public static final String STATUS_IN_OPERATION    = "IN_OPERATION";
    public static final String STATUS_DELIVERED       = "DELIVERED";
    public static final String STATUS_SETTLED         = "SETTLED";
    public static final String STATUS_CLOSED          = "CLOSED";
    public static final String STATUS_CANCELLED       = "CANCELLED";
    public static final String STATUS_VOID            = "VOID";

    /** 全部 9 个状态。 */
    public static final List<String> STATES = List.of(
            STATUS_DRAFT, STATUS_PENDING_CONFIRM, STATUS_CONFIRMED, STATUS_IN_OPERATION,
            STATUS_DELIVERED, STATUS_SETTLED, STATUS_CLOSED, STATUS_CANCELLED, STATUS_VOID);

    /** 终态: 进入后不可编辑业务字段, 只能读。 */
    public static final List<String> TERMINAL_STATES = List.of(
            STATUS_CLOSED, STATUS_CANCELLED, STATUS_VOID);

    /** 可编辑状态: 仅这两个。 */
    public static final List<String> EDITABLE_STATES = List.of(
            STATUS_DRAFT, STATUS_PENDING_CONFIRM);

    // ------------------------------------------------------------------ 状态机

    /** 状态机 code —— 与 {@code t_state_transition.state_machine_code} 的值一致。 */
    public static final String STATE_MACHINE_CODE = "ORDER";

    /**
     * 一条转换 (from, to, action)。
     *
     * @param from   起始状态 —— 对应 {@code t_state_transition.from_state}
     * @param to     目标状态 —— 对应 {@code t_state_transition.to_state}
     * @param action 动作名 —— 对应 {@code t_state_transition.transition_name}
     *               (列名是 {@code transition_name}, 不是 {@code action};
     *               本记录把它叫 action 是为了跟「用户做了什么动作」的语义对齐)
     */
    public record Transition(String from, String to, String action) {}

    /**
     * 15 条转换 —— 与 V42 的 {@code t_state_transition} seed 逐行对应
     * (from_state / to_state / transition_name 三个值全对得上)。
     *
     * <p><b>只用来取动作名, 不判合法性</b> —— 合法性一律走 {@code StateMachineEngine}
     * ({@code assertTransition} / {@code allowedNext})。本常量表不是状态机定义, 只是它的镜像。
     *
     * <p>设计文档 ({@code docs/business/order/05-委托单业务细化-v4.0.md}) 的 15 态里的
     * BOOKED / SHIPPED / IN_TRANSIT / ARRIVED_PORT / CLEARING / DELIVERING 这 6 态,
     * 其驱动方 (订舱 / 跟踪 / 报关模块) 都还没落库,
     * 本轮不 seed —— 否则 {@code allowedNext()} 会暴露一个走不通的死边。
     */
    public static final List<Transition> TRANSITIONS = List.of(
            new Transition(STATUS_DRAFT,           STATUS_PENDING_CONFIRM, "submit"),
            new Transition(STATUS_DRAFT,           STATUS_CANCELLED,       "cancel"),
            new Transition(STATUS_DRAFT,           STATUS_VOID,            "void"),
            new Transition(STATUS_PENDING_CONFIRM, STATUS_CONFIRMED,       "confirm"),
            new Transition(STATUS_PENDING_CONFIRM, STATUS_DRAFT,           "return"),
            new Transition(STATUS_PENDING_CONFIRM, STATUS_CANCELLED,       "cancel"),
            new Transition(STATUS_PENDING_CONFIRM, STATUS_VOID,            "void"),
            new Transition(STATUS_CONFIRMED,       STATUS_IN_OPERATION,    "start_operation"),
            new Transition(STATUS_CONFIRMED,       STATUS_CANCELLED,       "cancel"),
            new Transition(STATUS_CONFIRMED,       STATUS_VOID,            "void"),
            new Transition(STATUS_IN_OPERATION,    STATUS_DELIVERED,       "deliver"),
            new Transition(STATUS_IN_OPERATION,    STATUS_CANCELLED,       "cancel"),
            new Transition(STATUS_IN_OPERATION,    STATUS_VOID,            "void"),
            new Transition(STATUS_DELIVERED,       STATUS_SETTLED,         "settle"),
            new Transition(STATUS_SETTLED,         STATUS_CLOSED,          "close"));

    // ------------------------------------------------------------------ 枚举取值

    /** business_type 运输方式。 */
    public static final String BT_OCEAN_FCL  = "OCEAN_FCL";
    public static final String BT_OCEAN_LCL  = "OCEAN_LCL";
    public static final String BT_AIR        = "AIR";
    public static final String BT_RAIL       = "RAIL";
    public static final String BT_TRUCK      = "TRUCK";
    public static final String BT_EXPRESS    = "EXPRESS";
    public static final String BT_MULTIMODAL = "MULTIMODAL";
    public static final String BT_WAREHOUSE  = "WAREHOUSE";
    public static final List<String> BUSINESS_TYPES = List.of(
            BT_OCEAN_FCL, BT_OCEAN_LCL, BT_AIR, BT_RAIL,
            BT_TRUCK, BT_EXPRESS, BT_MULTIMODAL, BT_WAREHOUSE);

    /** trade_direction 贸易方向。 */
    public static final String TD_EXPORT           = "EXPORT";
    public static final String TD_IMPORT           = "IMPORT";
    public static final String TD_TRANSIT          = "TRANSIT";
    public static final String TD_TRANSIT_OVERLAND = "TRANSIT_OVERLAND";
    public static final List<String> TRADE_DIRECTIONS = List.of(
            TD_EXPORT, TD_IMPORT, TD_TRANSIT, TD_TRANSIT_OVERLAND);

    /** incoterms 贸易条款。 */
    public static final List<String> INCOTERMS = List.of(
            "EXW", "FCA", "FOB", "CFR", "CIF", "CPT", "CIP", "DAP", "DPU", "DDP");

    /** service_type 服务类型。 */
    public static final String ST_D2D = "D2D";
    public static final String ST_P2P = "P2P";
    public static final String ST_D2P = "D2P";
    public static final String ST_P2D = "P2D";
    public static final List<String> SERVICE_TYPES = List.of(ST_D2D, ST_P2P, ST_D2P, ST_P2D);

    /** calc_basis 计价基准。 */
    public static final List<String> CALC_BASES = List.of(
            "PER_SHIPMENT", "PER_CONTAINER", "PER_BOX", "PER_KG", "PER_CBM", "PER_CARGO");

    /** fee_category 费用大类。 */
    public static final List<String> FEE_CATEGORIES = List.of(
            "FREIGHT", "SURCHARGE", "CUSTOMS", "DELIVERY", "WAREHOUSE", "OTHER");

    /** fee_type 费用类型 —— 受控小枚举 (完整 50 项枚举留给下一切片的报价引擎)。 */
    public static final List<String> FEE_TYPES = List.of(
            "OCEAN_FREIGHT", "AIR_FREIGHT", "BAF", "THC", "DOC_FEE",
            "CUSTOMS", "DELIVERY", "OTHER");

    /** 服务项来源。 */
    public static final String SRC_MANUAL    = "MANUAL";
    public static final String SRC_QUOTATION = "QUOTATION";
    public static final List<String> SOURCES = List.of(SRC_MANUAL, SRC_QUOTATION);

    /** 汇率类型 —— 本轮只落字段, 无锁汇率逻辑。 */
    public static final List<String> EXCHANGE_RATE_TYPES = List.of("SPOT", "CONTRACT", "LOCKED");

    // ------------------------------------------------------------------ 其它

    /** 委托单号规则 code —— 对应 sys_number_rule.code='ORDER' (V4 已 seed)。 */
    public static final String NUMBER_RULE_CODE = "ORDER";

    /** 原因类字段的最小长度 (取消 / 作废 / 退回)。 */
    public static final int REASON_MIN_LENGTH = 5;
}
