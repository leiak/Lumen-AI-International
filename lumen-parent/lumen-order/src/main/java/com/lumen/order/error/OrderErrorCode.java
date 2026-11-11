package com.lumen.order.error;

import com.lumen.common.error.ErrorCode;
import lombok.Getter;

/**
 * 货运委托单域错误码 (Iteration 4.6).
 *
 * <p><b>风格沿用 finance / portal 的「数字前缀 + _ + 三位序号」</b> (如 {@code 404_001} / {@code 400_001} / {@code 409_003}),
 * 设计文档里的 {@code ERR_<DOMAIN>_<CODE>} 那一套在本仓没有先例, 不用。
 *
 * <p><b>号段取 {@code 4xx_1xx}</b> —— order 独占 {@code 1xx} 序号段
 * (400_101..400_108 / 404_101..404_102 / 409_101..409_103),
 * 与全仓现有码位**全部不重复** (已逐个核对全仓 10 个 {@code implements ErrorCode} 枚举,
 * 2026-11-11 复审时又核了一遍新增的 409_103 / 400_105 / 400_106 —— 三个码位在别的域都没出现过):
 * finance 占 404_001..404_007 / 400_001..400_023 / 409_001..409_005,
 * portal 占 401_003 / 403_001..403_003 / 404_001..404_005 / 409_001..409_002 /
 * 400_001..400_005 / 500_001,
 * cs 占 404_001..404_005 / 400_001..400_004 / 409_001..409_003 / 500_001 / 503_001,
 * dispatch 占 60001..60022, wms 占 50001..50017,
 * rbac 占 10001..10007, numbering 占 12001..12002, notification 占 13001..13002,
 * common 占 0 / 400 / 401 / 403 / 404 / 4001 / 500。
 * <p>(注: finance / cs / portal 三家之间**本来就有既有撞号** —— 如 {@code 404_005} 三家都有,
 * {@code 400_001}..{@code 400_004}、{@code 409_001}..{@code 409_003} 也都重叠;
 * cs 段与 finance / portal 的重叠是既有事实, 与 order 无关 —— order 走 {@code 1xx} 段, 不受影响。)
 *
 * <p><b>枚举参数顺序是 {@code (code, message, httpStatus)}</b> —— 照 {@code FinanceErrorCode}。
 * 注意 {@code PortalErrorCode} 的顺序**正好相反** ({@code (code, httpStatus, message)}),
 * 照抄错一个编译器不报错、运行时 httpStatus 全错。
 *
 * <p><b>字段级校验不进本枚举</b> —— {@code @NotNull} / {@code @NotBlank} / {@code @Positive} 这类
 * 单字段约束由 jakarta bean validation 兜住, 走 {@code GlobalExceptionHandler.handleValidation}
 * → HTTP 400 + {@code CommonErrorCode.PARAM_INVALID} (code 4001)。
 * 本枚举只收**跨字段 / 集合级 / 状态级**的规则。
 *
 * <p><b>注意 {@code ORDER_} 前缀在本仓有两套含义</b> —— 本枚举的
 * {@code ORDER_NOT_FOUND} / {@code ORDER_NOT_EDITABLE} 指的是**货运委托单**
 * ({@code t_order}), 而 {@code PortalErrorCode.ORDER_NOT_CANCELLABLE} /
 * {@code ORDER_PICKUP_WINDOW_*} 指的是**城配单** ({@code wms_delivery_order});
 * 两者都会出现在门户客户端拿到的响应里, 排查问题时别把两套单据混为一谈。
 */
@Getter
public enum OrderErrorCode implements ErrorCode {

    ORDER_NOT_FOUND(404_101, "委托单不存在", 404),
    CUSTOMER_NOT_FOUND(404_102, "客户不存在", 404),

    STATE_TRANSITION_INVALID(409_101, "当前状态不允许该操作", 409),
    ORDER_NOT_EDITABLE(409_102, "当前状态不可编辑", 409),
    /** 乐观锁冲突 —— 并发编辑, 命中 0 行。 */
    ORDER_CONFLICT(409_103, "委托单已被他人修改, 请刷新后重试", 409),

    CARGO_EMPTY(400_101, "货物明细不能为空", 400),
    SERVICE_ITEM_EMPTY(400_102, "服务项不能为空", 400),
    REASON_REQUIRED(400_103, "原因不能为空", 400),
    /**
     * 服务项金额合计必须大于 0 —— **Task 4 的确认守卫用** (confirm / confirmOwned)。
     */
    AMOUNT_INVALID(400_104, "服务项金额合计必须大于 0", 400),
    /** 数值越界 —— 超出列的 DECIMAL 宽度, 或不是有限数 (NaN / Infinity)。 */
    NUMERIC_OUT_OF_RANGE(400_105, "数值超出允许范围", 400),
    /** 受控词表取值非法 —— 详见 {@code OrderConstants} 的各组 List。 */
    ENUM_VALUE_INVALID(400_106, "枚举取值非法", 400),
    /**
     * 转换原因过长 —— 超过 {@code OrderConstants.REASON_MAX_LENGTH} (255) 字。
     *
     * <p><b>为什么必须在 service 层挡</b> —— V42 的 {@code cancel_reason} /
     * {@code close_reason} / {@code void_reason} 三列都是 {@code VARCHAR(255)};
     * 不挡上限的话, 客户端传 300 字 → MySQL 1406 Data too long →
     * {@code DataIntegrityViolationException} → {@code GlobalExceptionHandler} 没有对应分支
     * → 落 catch-all → <b>HTTP 500</b>。在 service 层挡掉才能给客户端一个正经的 400。
     */
    REASON_TOO_LONG(400_107, "原因过长", 400),
    /**
     * 转换原因过短 —— 少于 {@code OrderConstants.REASON_MIN_LENGTH} (5) 字。
     *
     * <p><b>为什么与 {@link #REASON_REQUIRED} 分开</b> —— 客户填了 3 个字,
     * 告诉他「原因不能为空」是骗人; 本仓一贯「一个失败一条码」。
     */
    REASON_TOO_SHORT(400_108, "原因不得少于 5 个字", 400);

    private final int code;
    private final String message;
    private final int httpStatus;

    OrderErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
