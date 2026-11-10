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
    // startOperation / deliver / settle / close + 门户端 confirmOwned / cancelOwned)
    // **在 Task 4 才声明并实现** —— 本 Task 的 OrderServiceImpl 只实现上面这 7 个方法,
    // 若此时就声明转换方法, 接口有抽象方法而实现类没有 → 编译不过。
}
