package com.lumen.order.service;

import com.lumen.common.api.PageResult;
import com.lumen.order.dto.CreateOrderRequest;
import com.lumen.order.dto.OrderDetailView;
import com.lumen.order.dto.OrderView;

import java.util.List;

/**
 * 货运委托单业务接口 (Iteration 4.6).
 *
 * <p><b>门户端也用这个接口</b> —— {@code com.lumen.customer.controller.PortalFreightOrderController}
 * (Task 7 新建) 调下面带 {@code customerId} 的那组方法拿客户归属边界, 门户控制器不做任何状态机判定。
 */
public interface OrderService {

    // ------------------------------------------------------------ 管理端

    /** 建草稿。委托单号由 {@code NumberGenerator.generate("ORDER")} 生成。 */
    OrderView create(CreateOrderRequest request);

    /** 编辑 (仅 DRAFT / PENDING_CONFIRM 可编辑, 否则抛 ORDER_NOT_EDITABLE)。 */
    OrderDetailView update(Long id, CreateOrderRequest request);

    /** 分页列表。 */
    PageResult<OrderView> page(long current, long size, String status, Long customerId,
                                String orderNo, String businessType);

    /** 详情。 */
    OrderDetailView get(Long id);

    // ------------------------------------------------------------ 门户端 (客户归属边界)

    /** 列当前客户的委托单。 */
    PageResult<OrderView> pageByCustomer(Long customerId, long current, long size, String status);

    /** 取详情。**归属不符 → ORDER_NOT_FOUND (404)**, 不是 403 —— 不能泄露「存在但不属于你」。 */
    OrderDetailView getOwned(Long id, Long customerId);

    /** 客户建单 (强制 DRAFT 态)。 */
    OrderView createForCustomer(Long customerId, CreateOrderRequest request);

    // ------------------------------------------------------------ 状态转换
    //
    // 11 个状态转换方法 (submit / confirm / returnToDraft / cancel / voidOrder /
    // startOperation / deliver / settle / close + 门户端 confirmOwned / cancelOwned)。
    //
    // <p><b>合法性一律由 {@code StateMachineEngine} 判定</b> —— 接口层不做状态判断,
    // 非法转换统一抛 {@code OrderErrorCode.STATE_TRANSITION_INVALID} (409_101)。
    // 带 {@code reason} 的三个方法 (returnToDraft / cancel / voidOrder) 与 close 的原因
    // 长度区间是 5..255 字 (见 {@code OrderConstants.REASON_MIN_LENGTH} /
    // {@code REASON_MAX_LENGTH})。

    /** 提交待客户确认 (DRAFT → PENDING_CONFIRM)。 */
    OrderDetailView submit(Long id);

    /** 客户确认 (PENDING_CONFIRM → CONFIRMED)。 */
    OrderDetailView confirm(Long id);

    /** 退回销售修改 (PENDING_CONFIRM → DRAFT)。 */
    OrderDetailView returnToDraft(Long id, String reason);

    /** 取消。 */
    OrderDetailView cancel(Long id, String reason);

    /** 作废。 */
    OrderDetailView voidOrder(Long id, String reason);

    /** 开始操作执行 (CONFIRMED → IN_OPERATION)。 */
    OrderDetailView startOperation(Long id);

    /** 交付 (IN_OPERATION → DELIVERED)。 */
    OrderDetailView deliver(Long id);

    /** 结算 (DELIVERED → SETTLED)。 */
    OrderDetailView settle(Long id);

    /** 关账 (SETTLED → CLOSED)。 */
    OrderDetailView close(Long id, String reason);

    /** 门户端确认 (归属校验 + 状态机)。 */
    OrderDetailView confirmOwned(Long id, Long customerId);

    /** 门户端取消 (归属校验 + 状态机)。 */
    OrderDetailView cancelOwned(Long id, Long customerId, String reason);
}
