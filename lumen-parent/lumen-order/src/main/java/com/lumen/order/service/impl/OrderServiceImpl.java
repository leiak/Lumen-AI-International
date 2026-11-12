package com.lumen.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.extension.outbox.EventBus;
import com.lumen.extension.state.StateMachineEngine;
import com.lumen.numbering.service.NumberGenerator;
import com.lumen.order.OrderConstants;
import com.lumen.order.dto.CargoRequest;
import com.lumen.order.dto.CargoView;
import com.lumen.order.dto.CreateOrderRequest;
import com.lumen.order.dto.OrderDetailView;
import com.lumen.order.dto.OrderView;
import com.lumen.order.dto.ServiceItemRequest;
import com.lumen.order.dto.ServiceItemView;
import com.lumen.order.entity.Order;
import com.lumen.order.entity.OrderCargo;
import com.lumen.order.entity.OrderServiceItem;
import com.lumen.order.error.OrderErrorCode;
import com.lumen.order.event.OrderStateChangedEvent;
import com.lumen.order.exception.OrderException;
import com.lumen.order.mapper.OrderCargoMapper;
import com.lumen.order.mapper.OrderMapper;
import com.lumen.order.mapper.OrderServiceItemMapper;
import com.lumen.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 货运委托单业务实现 (Iteration 4.6).
 *
 * <p><b>租户</b>: {@code tenant_id} 由 {@code MyMetaObjectHandler} 在插入时按
 * {@code TenantContext} 自动填; 查询侧由 {@code MybatisPlusConfig} 的
 * {@code TenantLineInnerInterceptor} 自动加 {@code AND tenant_id = ?}。本类**不手动**设 tenantId。
 *
 * <p><b>软删</b>: 三个实体的 {@code deleted} 都带 {@code @TableLogic}, MP 自动补 {@code deleted = 0}。
 *
 * <p><b>状态机</b>: 状态合法性**只**由 {@link StateMachineEngine} 判定 (表驱动,
 * 种子在 V42 的 {@code t_state_transition}); {@code OrderConstants.TRANSITIONS}
 * 只是那张表的 Java 侧镜像, 不参与判定。
 *
 * <p><b>事件</b>: 每次状态转换发一条 {@link OrderStateChangedEvent} 走 {@link EventBus}
 * —— {@code DefaultEventBus.publish} 在**同一事务内**写 {@code t_event_outbox} 行,
 * 所以所有转换方法都带 {@code @Transactional}。
 */
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderCargoMapper cargoMapper;
    private final OrderServiceItemMapper serviceItemMapper;
    private final NumberGenerator numberGenerator;
    /** 状态机引擎 —— 状态合法性**只**由它判定 (t_state_transition 表驱动)。 */
    private final StateMachineEngine stateMachineEngine;
    /** 事件总线 —— 每次状态转换发一条 {@link OrderStateChangedEvent}。 */
    private final EventBus eventBus;

    /** 分页 size 上限 —— 超过 200 一律截到 200。 */
    private static final long MAX_PAGE_SIZE = 200L;
    /** size <= 0 时的兜底值。 */
    private static final long DEFAULT_PAGE_SIZE = 20L;

    // ------------------------------------------------------------ 数值列的 DECIMAL 口径
    //
    // 列宽来自 V42 迁移 (自己又核了一遍 t_order / t_order_cargo / t_order_service_item 三张表):
    //   DECIMAL(18,3) → pkg_qty / weight_kg / net_weight_kg / volume_cbm /
    //                   length_cm / width_cm / height_cm /
    //                   t_order_service_item.qty /
    //                   t_order.total_qty / total_weight_kg / total_volume_cbm
    //   DECIMAL(18,2) → cargo_value / t_order_service_item.amount / cost_amount
    //   DECIMAL(18,4) → t_order_service_item.unit_price

    /** {@code DECIMAL(18,3)} 的小数位数。 */
    private static final int SCALE_3 = 3;
    /** {@code DECIMAL(18,2)} 的小数位数。 */
    private static final int SCALE_2 = 2;
    /** {@code DECIMAL(18,4)} 的小数位数。 */
    private static final int SCALE_4 = 4;

    /** {@code DECIMAL(18,2)} 的最大绝对值。 */
    private static final BigDecimal MAX_18_2 = new BigDecimal("9999999999999999.99");
    /** {@code DECIMAL(18,3)} 的最大绝对值。 */
    private static final BigDecimal MAX_18_3 = new BigDecimal("999999999999999.999");
    /** {@code DECIMAL(18,4)} 的最大绝对值。 */
    private static final BigDecimal MAX_18_4 = new BigDecimal("99999999999999.9999");

    // ------------------------------------------------------------ 管理端

    @Override
    @Transactional
    public OrderView create(CreateOrderRequest request) {
        return createInternal(request, null, OrderConstants.STATUS_DRAFT);
    }

    /**
     * 编辑委托单 (仅 DRAFT / PENDING_CONFIRM 可编辑)。
     *
     * <p><b>编辑不改归属</b> —— {@code customerId} 只在建单时定; 编辑路径一律不动它,
     * 客户端在 body 里带 {@code customerId} 一律无效。要把单子换到别的客户名下, 请另开一张单。
     *
     * <p><b>编辑是部分覆盖, 不是全量替换</b> —— MP 默认 {@code updateStrategy = NOT_NULL},
     * 实体上为 null 的字段不进 SET 子句, 客户端漏传的字段**保留库里的旧值**。
     * <p><b>但这只对「可空的标量字段」成立</b> —— 头部 4 个枚举
     * (businessType / tradeDirection / incoterms / serviceType) 与 cargo / serviceItems
     * 两个集合是**必填**的: 枚举为空或非法 → {@link OrderErrorCode#ENUM_VALUE_INVALID} (400_106),
     * 集合为空 → {@link OrderErrorCode#CARGO_EMPTY} (400_101) /
     * {@link OrderErrorCode#SERVICE_ITEM_EMPTY} (400_102)。
     * 换句话说 PUT **不能**只带要改的那几个字段 —— 这几个是必带的。
     *
     * <p><b>已知限制: 可空列无法被显式清空</b> —— 同一条 NOT_NULL 策略的另一面:
     * 客户端传 {@code null} 表示「不改」, 没有办法表达「把这个可空字段置空」。
     * 需要清空的字段 (如 {@code remark} / {@code cargoDescription}) 当前只能靠
     * 其它手段 (直接改库或下一切片的「显式清空」入参) 处理。
     *
     * <p><b>乐观锁</b> —— 头部 UPDATE 命中 0 行说明期间被别人改过, 抛
     * {@link OrderErrorCode#ORDER_CONFLICT}; 冲突判定**在明细软删/重插之前**,
     * 所以冲突时一行明细都不动。
     *
     * <p><b>乐观锁的边界 (别把它当成防住了 lost update)</b> —— 实体上的 version 来自
     * 请求开始时的 {@code selectById}, 客户端**不能**传 version。所以乐观锁只挡
     * 「本次请求执行窗口内」的并发; 「用户 A 打开表单 → 用户 B 改过 → A 提交」这种
     * **陈旧表单覆盖仍然会静默发生**。要真 CAS, version 得进 DTO 由客户端回传并比对 ——
     * 本轮没做, 这里写明边界。
     *
     * <p><b>两条 UPDATE 的 version 是接力的</b> —— 头部 UPDATE 走完, MP 的
     * {@code OptimisticLockerInnerInterceptor} 会把新版本 (v+1) <b>写回实体</b>;
     * 紧接着的聚合回写那条 {@code agg} 因此带的是 v+1, 它自己再顶到 v+2。
     * 两步用的是同一个 {@code order} 实例, 所以这个接力成立 (v → v+1 → v+2)。
     *
     * <p><b>{@code updated_at} 的前进靠的是聚合回写那条 UPDATE</b> —— 头部 UPDATE 的
     * {@code order} 是**从库里加载来的实体**, {@code updated_at} 已有值; 而 MP 的
     * {@code strictUpdateFill} 语义是「已有值不覆盖」→ 头部 UPDATE 写回去的
     * {@code updated_at} 还是旧值 (DDL 上的 {@code ON UPDATE CURRENT_TIMESTAMP}
     * 也因此被显式赋值压掉)。真正把它刷成 now 的是聚合回写那条 {@code agg}
     * —— 它是新实体, {@code updated_at} 为 null, 被 fill 成 now。
     * 也就是说「{@code updated_at} 会前进」这件事**依赖聚合回写恰好跑在最后**;
     * 哪天聚合回写被挪走或改成异步, {@code updated_at} 会静默冻结 —— 这里写明这个耦合。
     */
    @Override
    @Transactional
    public OrderDetailView update(Long id, CreateOrderRequest request) {
        Order order = requireOrder(id);
        if (!OrderConstants.EDITABLE_STATES.contains(order.getOrderStatus())) {
            throw new OrderException(OrderErrorCode.ORDER_NOT_EDITABLE,
                    "当前状态 " + order.getOrderStatus() + " 不可编辑");
        }
        List<CargoRequest> cargo = requireCargo(request);
        List<ServiceItemRequest> items = requireServiceItems(request);
        requireServiceItemNames(items);
        requireEnums(request);
        requireNumerics(request);
        // 聚合同样在写库之前算 (与建单路径同一口径)
        CargoAggregate summary = aggregateCargo(cargo);

        applyHeader(order, request);
        applyUpdateOverrides(order, request);
        int rows = orderMapper.updateById(order);
        if (rows == 0) {
            throw new OrderException(OrderErrorCode.ORDER_CONFLICT,
                    "委托单已被他人修改, 请刷新后重试");
        }

        replaceChildren(order, cargo, items, summary);
        return get(id);
    }

    @Override
    public PageResult<OrderView> page(long current, long size, String status, Long customerId,
                                       String orderNo, String businessType) {
        long effectiveSize = Math.min(size <= 0 ? DEFAULT_PAGE_SIZE : size, MAX_PAGE_SIZE);
        long pageNum = current <= 0 ? 1 : current;

        LambdaQueryWrapper<Order> q = new LambdaQueryWrapper<Order>()
                .orderByDesc(Order::getCreatedAt);
        if (status != null && !status.isBlank()) {
            q.eq(Order::getOrderStatus, status);
        }
        if (customerId != null) {
            q.eq(Order::getCustomerId, customerId);
        }
        if (businessType != null && !businessType.isBlank()) {
            q.eq(Order::getBusinessType, businessType);
        }
        if (orderNo != null && !orderNo.isBlank()) {
            q.like(Order::getOrderNo, orderNo);
        }

        Page<Order> result = orderMapper.selectPage(new Page<>(pageNum, effectiveSize), q);
        List<OrderView> views = result.getRecords().stream().map(this::toView).toList();
        return PageResult.of(views, result.getTotal(), pageNum, effectiveSize);
    }

    @Override
    public OrderDetailView get(Long id) {
        Order order = requireOrder(id);
        return toDetail(order);
    }

    // ------------------------------------------------------------ 门户端

    @Override
    public PageResult<OrderView> pageByCustomer(Long customerId, long current, long size, String status) {
        // customerId 为 null 时 MP 会拼出 `customer_id = null` → 恒空页、且不报错 ——
        // 与 getOwned 显式挡 null 的口径不一致, 这里同样显式挡成业务异常。
        if (customerId == null) {
            throw new OrderException(OrderErrorCode.CUSTOMER_NOT_FOUND, "缺少客户身份");
        }
        return page(current, size, status, customerId, null, null);
    }

    @Override
    public OrderDetailView getOwned(Long id, Long customerId) {
        // customerId 为 null 时下面的 equals 会 NPE → 500; 显式挡成业务异常
        if (customerId == null) {
            throw new OrderException(OrderErrorCode.CUSTOMER_NOT_FOUND, "缺少客户身份");
        }
        Order order = orderMapper.selectById(id);
        // 归属不符一律按「不存在」处理 —— 不能泄露「存在但不属于你」
        if (order == null || !customerId.equals(order.getCustomerId())) {
            throw new OrderException(OrderErrorCode.ORDER_NOT_FOUND);
        }
        return toDetail(order);
    }

    /**
     * 客户自助建单。
     *
     * <p><b>customerId 一律以本方法的入参为准</b> —— 门户端 Task 7 会新建不带 customerId 字段的
     * DTO ({@code com.lumen.customer.dto.PortalFreightOrderRequest}); 本方法目前**复用管理端的
     * {@link CreateOrderRequest}** (那个 DTO 是带 customerId 的), 因此这里**强制覆盖** customerId ——
     * 客户端在 body 里传什么都不生效, 保证「客户只能给自己建单」。
     *
     * <p><b>这条边界有单测锁着</b> —— 单测断言「body 里带 customerId=999 时, 落库的归属仍是方法入参」。
     * 改动 {@link #createInternal} 里归属取值的逻辑前先看那条用例: 把那段逻辑「顺手简化」成
     * 优先取 {@code request.customerId()} (看起来完全合理) 时, 它会立刻转红。
     */
    @Override
    @Transactional
    public OrderView createForCustomer(Long customerId, CreateOrderRequest request) {
        if (customerId == null) {
            throw new OrderException(OrderErrorCode.CUSTOMER_NOT_FOUND, "缺少客户身份");
        }
        return createInternal(request, customerId, OrderConstants.STATUS_DRAFT);
    }

    // ------------------------------------------------------------ 状态转换
    //
    // 9 个管理端转换 + 2 个门户端转换 (confirmOwned / cancelOwned)。
    // 每个转换都走同一条收口: requireOrder → 各自的守卫 → requireTransition →
    // applyTransition (落状态 + 发事件 + 返回详情)。
    //
    // <p><b>所有转换方法都标了 @Transactional</b> —— DefaultEventBus.publish 的
    // 传播级别是 Propagation.MANDATORY, 调用方没有事务时它直接抛异常;
    // 转换方法必须自己有事务, 才能保证「状态 UPDATE + outbox 行」同事务落库。

    @Override
    @Transactional
    public OrderDetailView submit(Long id) {
        Order o = requireOrder(id);
        requireTransition(o, OrderConstants.STATUS_PENDING_CONFIRM, "submit");
        requireBasicsComplete(o);
        o.setSubmittedAt(LocalDateTime.now());
        return applyTransition(o, OrderConstants.STATUS_PENDING_CONFIRM, "submit", null);
    }

    @Override
    @Transactional
    public OrderDetailView confirm(Long id) {
        Order o = requireOrder(id);
        requireTransition(o, OrderConstants.STATUS_CONFIRMED, "confirm");
        requirePositiveAmount(o);
        o.setConfirmedAt(LocalDateTime.now());
        return applyTransition(o, OrderConstants.STATUS_CONFIRMED, "confirm", null);
    }

    @Override
    @Transactional
    public OrderDetailView returnToDraft(Long id, String reason) {
        Order o = requireOrder(id);
        requireReason(reason);
        requireTransition(o, OrderConstants.STATUS_DRAFT, "return");
        // 退回的原因**只进事件流** —— V42 的 t_order 没有 return_reason 列,
        // 本轮不为此加列 (加列要动迁移脚本, 超出本轮范围)。
        return applyTransition(o, OrderConstants.STATUS_DRAFT, "return", reason);
    }

    @Override
    @Transactional
    public OrderDetailView cancel(Long id, String reason) {
        Order o = requireOrder(id);
        requireReason(reason);
        requireTransition(o, OrderConstants.STATUS_CANCELLED, "cancel");
        o.setCancelReason(reason);
        return applyTransition(o, OrderConstants.STATUS_CANCELLED, "cancel", reason);
    }

    @Override
    @Transactional
    public OrderDetailView voidOrder(Long id, String reason) {
        Order o = requireOrder(id);
        requireReason(reason);
        requireTransition(o, OrderConstants.STATUS_VOID, "void");
        o.setVoidReason(reason);
        return applyTransition(o, OrderConstants.STATUS_VOID, "void", reason);
    }

    @Override
    @Transactional
    public OrderDetailView startOperation(Long id) {
        Order o = requireOrder(id);
        requireTransition(o, OrderConstants.STATUS_IN_OPERATION, "start_operation");
        return applyTransition(o, OrderConstants.STATUS_IN_OPERATION, "start_operation", null);
    }

    @Override
    @Transactional
    public OrderDetailView deliver(Long id) {
        Order o = requireOrder(id);
        requireTransition(o, OrderConstants.STATUS_DELIVERED, "deliver");
        o.setDeliveredAt(LocalDateTime.now());
        return applyTransition(o, OrderConstants.STATUS_DELIVERED, "deliver", null);
    }

    @Override
    @Transactional
    public OrderDetailView settle(Long id) {
        Order o = requireOrder(id);
        requireTransition(o, OrderConstants.STATUS_SETTLED, "settle");
        o.setSettledAt(LocalDateTime.now());
        return applyTransition(o, OrderConstants.STATUS_SETTLED, "settle", null);
    }

    @Override
    @Transactional
    public OrderDetailView close(Long id, String reason) {
        Order o = requireOrder(id);
        // 关账也要原因 —— 与 cancel / voidOrder / returnToDraft 同口径:
        // controller 的 @RequestBody(required = false) 允许 body 缺省,
        // 缺省时由这里抛 REASON_REQUIRED (400), 而不是让空原因静默落库。
        requireReason(reason);
        requireTransition(o, OrderConstants.STATUS_CLOSED, "close");
        o.setCloseReason(reason);
        o.setClosedAt(LocalDateTime.now());
        return applyTransition(o, OrderConstants.STATUS_CLOSED, "close", reason);
    }

    @Override
    @Transactional
    public OrderDetailView confirmOwned(Long id, Long customerId) {
        Order o = requireOwned(id, customerId);
        requireTransition(o, OrderConstants.STATUS_CONFIRMED, "confirm");
        requirePositiveAmount(o);
        o.setConfirmedAt(LocalDateTime.now());
        return applyTransition(o, OrderConstants.STATUS_CONFIRMED, "confirm", null);
    }

    @Override
    @Transactional
    public OrderDetailView cancelOwned(Long id, Long customerId, String reason) {
        Order o = requireOwned(id, customerId);
        // 门户撤单的状态闸在 requireReason 之前 —— 「这张单能不能撤」比
        // 「原因写够 5 个字没有」更前置, 客户点取消时先撞上的应该是这个。
        requirePortalCancelable(o);
        requireReason(reason);
        requireTransition(o, OrderConstants.STATUS_CANCELLED, "cancel");
        o.setCancelReason(reason);
        return applyTransition(o, OrderConstants.STATUS_CANCELLED, "cancel", reason);
    }

    /**
     * 门户端撤单的状态守卫 —— 客户只能在**尚未被确认**的两个态里自助撤单。
     *
     * <p><b>为什么要有这道闸</b> —— {@code requireTransition} 走的是通用状态机,
     * 它按 {@code t_state_transition} 判「这个转换存不存在」。而 V42 里
     * {@code CONFIRMED → CANCELLED} / {@code IN_OPERATION → CANCELLED} 的
     * {@code guard_expression} 是 {@code role:order_supervisor} —— 那是**我方主管**
     * 才能做的撤单动作 (已确认 = 合同已成立)。门户端若直接走通用状态机, 等于把
     * 主管动作开放给客户: 客户可以自己把一单已确认的委托单撤掉。
     *
     * <p><b>客户能自助撤的只有 {@code DRAFT} / {@code PENDING_CONFIRM}</b> ——
     * 这两条在 V42 里的 guard 是 {@code role:order_sales}, 都发生在「销售受理之前」。
     * 从 {@code DRAFT} 起算是因为门户建单就落 {@code DRAFT}, 客户改主意要有出口
     * (门户没有编辑/删除端点)。
     *
     * <p>已确认及以后的撤单走管理端 {@code POST /api/v1/orders/{id}/cancel}
     * ({@code order:cancel}), 由销售/主管处理。
     *
     * <p><b>状态集合不在本方法里写死</b> —— 取
     * {@link OrderConstants#PORTAL_CANCELABLE_STATES}: 门户端放行闸 (这里) 与
     * 门户端按钮可见性 (customer 域的 {@code PortalFreightOrderController.portalNextStates})
     * 必须同口径, 否则会出现「前端亮出取消按钮、点下去 409」或「能撤却不给按钮」。
     */
    private void requirePortalCancelable(Order o) {
        String s = o.getOrderStatus();
        if (OrderConstants.PORTAL_CANCELABLE_STATES.contains(s)) {
            return;
        }
        throw new OrderException(OrderErrorCode.STATE_TRANSITION_INVALID,
                "当前状态不允许客户自行取消, 请联系客服处理");
    }

    // ------------------------------------------------------------ 内部

    /**
     * @param customerIdOverride 非空时**覆盖** {@code request.customerId()} (门户端路径);
     *                           为空时用 {@code request.customerId()} (管理端路径)。
     */
    private OrderView createInternal(CreateOrderRequest request, Long customerIdOverride, String status) {
        List<CargoRequest> cargo = requireCargo(request);
        List<ServiceItemRequest> items = requireServiceItems(request);
        requireServiceItemNames(items);
        requireEnums(request);
        requireNumerics(request);
        // 聚合同样在写库之前算 —— 合计撑爆 DECIMAL(18,3) 时, 主表一行都还没插
        CargoAggregate summary = aggregateCargo(cargo);

        Order order = new Order();
        // 归属只在建单时定 —— 编辑路径永远不动 customerId (见 applyHeader 的说明)
        Long effectiveCustomerId = customerIdOverride != null ? customerIdOverride : request.customerId();
        // 归属不能为空 —— t_order.customer_id 是 NOT NULL, 空值一路走到 INSERT 就是 MySQL 1364 → 500。
        // DTO 上的 @NotNull 只在 HTTP 路径生效, 内部调用方绕过校验器 → 这里必须自己挡一道。
        if (effectiveCustomerId == null) {
            throw new OrderException(OrderErrorCode.CUSTOMER_NOT_FOUND, "缺少客户身份");
        }
        order.setCustomerId(effectiveCustomerId);
        applyHeader(order, request);
        applyCreateDefaults(order, request);
        order.setOrderNo(numberGenerator.generate(OrderConstants.NUMBER_RULE_CODE));
        order.setOrderStatus(status);
        orderMapper.insert(order);

        replaceChildren(order, cargo, items, summary);
        return toView(order);
    }

    /**
     * 货物明细的集合级校验 —— **列表为空** 或 **列表里含 null 元素** 都算不合法。
     *
     * <p><b>为什么要挡 null 元素</b> —— 客户端在 body 里传 {@code "cargo":[null]} 时,
     * Jackson 会把 null 原样放进 {@code List} (实测: Jackson 2.15.4 反序列化本仓的
     * {@link CreateOrderRequest}, 得到的 {@code cargo().get(0) == null});
     * DTO 上的 {@code @Valid} 也**挡不住** —— Hibernate Validator 8.0.1.Final
     * 对集合里的 null 元素不产生任何违规 (元素为 null 时该元素被跳过, 非 null 元素才级联校验),
     * 于是 null 一路流到 {@link #requireNumerics} / {@link #aggregateCargo} 的循环里
     * → {@code NullPointerException} → {@code GlobalExceptionHandler} 的 catch-all → HTTP 500。
     * 「参数不合法应该是 400 而不是 500」是本仓这几轮的硬口径, 所以这里自己挡一道。
     *
     * <p><b>码位复用 {@link OrderErrorCode#CARGO_EMPTY}</b> —— 枚举里没有「明细含空行」这一档;
     * {@code CARGO_EMPTY} 的消息「货物明细不能为空」读作「一行货物明细都不许是空的」时
     * 也成立 (null 元素就是一行空的明细)。不新增码位是因为 {@code OrderErrorCode} 本轮不允许改动。
     */
    private List<CargoRequest> requireCargo(CreateOrderRequest request) {
        List<CargoRequest> cargo = request.cargo();
        if (cargo == null || cargo.isEmpty()) {
            throw new OrderException(OrderErrorCode.CARGO_EMPTY);
        }
        for (CargoRequest row : cargo) {
            if (row == null) {
                throw new OrderException(OrderErrorCode.CARGO_EMPTY, "货物明细含空行 (null 元素)");
            }
        }
        return cargo;
    }

    /**
     * 服务项的集合级校验 —— **列表为空** 或 **列表里含 null 元素** 都算不合法。
     *
     * <p>码位复用的理由与 {@link #requireCargo} 相同: {@code SERVICE_ITEM_EMPTY} 的消息
     * 「服务项不能为空」读作「一行服务项都不许是空的」时也成立 (null 元素就是一行空的服务项)。
     */
    private List<ServiceItemRequest> requireServiceItems(CreateOrderRequest request) {
        List<ServiceItemRequest> items = request.serviceItems();
        if (items == null || items.isEmpty()) {
            throw new OrderException(OrderErrorCode.SERVICE_ITEM_EMPTY);
        }
        for (ServiceItemRequest row : items) {
            if (row == null) {
                throw new OrderException(OrderErrorCode.SERVICE_ITEM_EMPTY, "服务项含空行 (null 元素)");
            }
        }
        return items;
    }

    /**
     * 服务项的费用名称非空校验 —— {@code t_order_service_item.fee_name} 是
     * {@code VARCHAR(128) NOT NULL} (V42 迁移)。
     *
     * <p><b>为什么 service 层要再挡一道</b> —— DTO 上的 {@code @NotBlank} 只在 HTTP 路径生效
     * (门户端 Task 7 的 DTO 与内部调用方都绕过校验器); 空白的 feeName 一路走到 INSERT:
     * MP 的 insert 策略是 {@code NOT_NULL} (null 字段不进 INSERT 的列清单), 而 {@code fee_name}
     * 是 NOT NULL 且没有 DEFAULT → MySQL 报 1364 → Spring 翻译成
     * {@code DataIntegrityViolationException} → {@code GlobalExceptionHandler} 对它没有专门分支,
     * 落到 catch-all → HTTP 500。空白串 ("   ") 则是另一个坑: 它能进 INSERT, 但落库是一条
     * 没有名字的费用行 —— 所以这里按「null 与空白同等对待」挡掉。
     *
     * <p><b>码位复用 {@link OrderErrorCode#SERVICE_ITEM_EMPTY}</b> —— 现有的 11 个码位里
     * 没有「费用名称为空」这一档; {@code SERVICE_ITEM_EMPTY} 的消息「服务项不能为空」
     * 读作「一行服务项不许是空的」时也成立 (费用名空的服务项就是没填全的服务项)。
     * {@code AMOUNT_INVALID} 不用 —— 它的语义是「服务项金额合计必须大于 0」, 与「名称为空」对不上。
     * 不新增码位是因为 {@code OrderErrorCode} 本轮不允许改动。
     */
    private static void requireServiceItemNames(List<ServiceItemRequest> items) {
        for (ServiceItemRequest item : items) {
            if (item.feeName() == null || item.feeName().isBlank()) {
                throw new OrderException(OrderErrorCode.SERVICE_ITEM_EMPTY,
                        "服务项的费用名称不能为空 (feeName)");
            }
        }
    }

    /**
     * 受控词表校验 —— 头部 4 个必填枚举 + 服务项的 3 个。
     *
     * <p><b>为什么落在 service 而不是 DTO</b> —— DTO 上要用 {@code @Pattern} 把每个枚举值
     * 抄成正则, 抄漏一处就是一个洞, 而且码位会变成 {@code PARAM_INVALID (4001)};
     * {@code OrderConstants} 的各组 List 本来就是「受控词表的唯一权威」, 在 service 层校验
     * 才能一处定义、一处引用, 码位也留在 {@link OrderErrorCode} 里 (ENUM_VALUE_INVALID)。
     *
     * <p><b>可选字段只在非空时校验</b> —— {@code feeCategory} 是可空列, null / blank 放行。
     */
    private static void requireEnums(CreateOrderRequest request) {
        requireEnum(request.businessType(), OrderConstants.BUSINESS_TYPES, "businessType");
        requireEnum(request.tradeDirection(), OrderConstants.TRADE_DIRECTIONS, "tradeDirection");
        requireEnum(request.incoterms(), OrderConstants.INCOTERMS, "incoterms");
        requireEnum(request.serviceType(), OrderConstants.SERVICE_TYPES, "serviceType");
        for (ServiceItemRequest item : request.serviceItems()) {
            requireEnum(item.feeType(), OrderConstants.FEE_TYPES, "feeType");
            requireEnum(item.calcBasis(), OrderConstants.CALC_BASES, "calcBasis");
            if (item.feeCategory() != null && !item.feeCategory().isBlank()) {
                requireEnum(item.feeCategory(), OrderConstants.FEE_CATEGORIES, "feeCategory");
            }
        }
    }

    /** 取值不在受控词表里 → ENUM_VALUE_INVALID (400_106)。 */
    private static void requireEnum(String value, List<String> allowed, String field) {
        if (value == null || !allowed.contains(value)) {
            throw new OrderException(OrderErrorCode.ENUM_VALUE_INVALID,
                    field + " 取值非法: " + value + " (合法取值: " + String.join(" / ", allowed) + ")");
        }
    }

    /**
     * 数值列的上界预检 —— **在任何写库之前**跑一遍。
     *
     * <p><b>为什么要有这一层</b> —— 越界的数值在 {@link #replaceChildren} 里转换时才会被发现,
     * 那时主表已经 INSERT/UPDATE 过了。虽然 {@code @Transactional} 会把整段回滚 (不会留下脏数据),
     * 但「参数不合法就别写库」是更干净的口径 —— 失败请求一行都不该碰数据库。
     */
    private static void requireNumerics(CreateOrderRequest request) {
        for (CargoRequest r : request.cargo()) {
            checkNumber(r.pkgQty(), SCALE_3, "pkg_qty");
            checkNumber(r.weightKg(), SCALE_3, "weight_kg");
            checkNumber(r.netWeightKg(), SCALE_3, "net_weight_kg");
            checkNumber(r.volumeCbm(), SCALE_3, "volume_cbm");
            checkNumber(r.lengthCm(), SCALE_3, "length_cm");
            checkNumber(r.widthCm(), SCALE_3, "width_cm");
            checkNumber(r.heightCm(), SCALE_3, "height_cm");
            checkNumber(r.cargoValue(), SCALE_2, "cargo_value");
        }
        for (ServiceItemRequest s : request.serviceItems()) {
            checkNumber(s.qty(), SCALE_3, "qty");
            checkNumber(s.unitPrice(), SCALE_4, "unit_price");
            checkNumber(s.amount(), SCALE_2, "amount");
            checkNumber(s.costAmount(), SCALE_2, "cost_amount");
        }
    }

    /** null 直接放行 (可空列本来就不该有值)。 */
    private static void checkNumber(Double v, int scale, String column) {
        if (v != null) {
            toColumnValue(v, scale, column);
        }
    }

    /**
     * 头部字段的部分覆盖 —— 建单与编辑走同一套 setter 口径。
     *
     * <p><b>不写 {@code customerId}</b> —— 归属只在建单时定 ({@link #createInternal} 里单独设),
     * 编辑路径一律不动它: 否则管理端一次 PUT 带 {@code customerId=999} 就能把单子转到别的客户名下。
     * 要换客户请另开一张单。
     *
     * <p><b>编辑是部分覆盖, 不是全量替换</b> —— MP 默认 {@code updateStrategy = NOT_NULL},
     * 实体上为 null 的字段不进 SET 子句, 客户端漏传的字段**保留库里的旧值** (而不是被清空)。
     *
     * <p><b>{@code settlementCurrency} / {@code isOversized} 不在这里</b> —— 这两列的「缺省」语义
     * 两条路径不同 (建单落 DDL 默认值, 编辑保留库中原值), 由 {@link #applyCreateDefaults} /
     * {@link #applyUpdateOverrides} 各自决定。
     */
    private void applyHeader(Order order, CreateOrderRequest request) {
        order.setBusinessType(request.businessType());
        order.setTradeDirection(request.tradeDirection());
        order.setIncoterms(request.incoterms());
        order.setServiceType(request.serviceType());
        order.setOriginAddress(request.originAddress());
        order.setDestAddress(request.destAddress());
        order.setCargoReadyDate(request.cargoReadyDate());
        order.setEtd(request.etd());
        order.setEta(request.eta());
        order.setContainerQty(request.containerQty());
        order.setContainerTypeCode(request.containerTypeCode());
        order.setMarksAndNos(request.marksAndNos());
        order.setCargoDescription(request.cargoDescription());
        order.setTemperatureRange(request.temperatureRange());
        order.setRemark(request.remark());
    }

    /**
     * 建单路径的缺省值 —— 对齐 DDL 的 {@code settlement_currency CHAR(3) NOT NULL DEFAULT 'CNY'}
     * 与 {@code is_oversized TINYINT NOT NULL DEFAULT 0}。
     *
     * <p>请求里的 {@code settlementCurrency} 为 null/blank → 落 {@code CNY};
     * {@code isOversized} 为 null → 落 {@code 0}。
     */
    private static void applyCreateDefaults(Order order, CreateOrderRequest request) {
        order.setSettlementCurrency(
                request.settlementCurrency() == null || request.settlementCurrency().isBlank()
                        ? "CNY" : request.settlementCurrency());
        order.setIsOversized(Boolean.TRUE.equals(request.isOversized()) ? 1 : 0);
    }

    /**
     * 编辑路径的覆盖 —— **null 一律保留库里原值**, 与 MP 的 NOT_NULL 更新策略同口径。
     *
     * <p>{@code settlementCurrency} 为 null **或 blank** → 不设 (库里是 USD 就还是 USD);
     * {@code isOversized} 为 null → 不设; 非 null 则正常写入 —— 所以「取消超限标记」传
     * {@code false} → 落 {@code 0}, 清除动作仍然可用。
     *
     * <p><b>为什么不能一刀切成默认值</b> —— 一个不带这两字段的部分更新请求会把库里的
     * USD / {@code is_oversized=1} 冲掉, 用户没碰过的字段被静默改写。
     */
    private static void applyUpdateOverrides(Order order, CreateOrderRequest request) {
        if (request.settlementCurrency() != null && !request.settlementCurrency().isBlank()) {
            order.setSettlementCurrency(request.settlementCurrency());
        }
        if (request.isOversized() != null) {
            order.setIsOversized(request.isOversized() ? 1 : 0);
        }
    }

    /**
     * 用明细整体替换子表, 然后把聚合值回写主表。
     *
     * <p>先软删旧的再插新的 —— 简单且不会留下孤儿行; 委托单明细量小 (几十行),
     * 整表替换的写放大可以接受。
     *
     * <p><b>已知代价: 明细历史行线性堆积, 且明细 id 每次编辑都会变</b> ——
     * {@code @TableLogic} 的软删只是把旧行的 {@code deleted} 置 1, 行还在表里;
     * 每次编辑都插一批新行, 编辑 N 次 → 明细表里躺着 N 批历史行 (第 N 批之外全是 deleted=1)。
     * 明细的 {@code id} 也随之每次重新分配 (旧 id 不会复用) —— 任何按明细 id 的外部引用
     * (下游单据、报表快照) 都不能假定它稳定。本轮不做明细的版本化对比, 只在这里写明。
     *
     * <p>聚合值同时落在两处: (1) 一条**只含 4 个聚合列 + {@code version} + MP 自动维护的
     * {@code updated_at}** 的 UPDATE 语句 (SET 子句实际 6 列), 不碰主表其它业务列 ——
     * {@code updated_at} 必然进 SET: {@code BaseEntity.updatedAt} 标的是 {@code fill = INSERT_UPDATE},
     * 而 MP 的 {@code TableFieldInfo.getSqlSet} 对 fill 字段**不做 null 判断**;
     * {@code version} 进 SET 是因为它非 null, 且默认 {@code updateStrategy = NOT_NULL} 的
     * {@code <if>} 判断放行;
     * (2) 传进来的 {@code order} 实例 —— {@code create()} 直接拿它拼 {@code OrderView} 返回,
     * 聚合值必须在这份实例上可见, 否则建单接口返回的汇总恒为空。
     *
     * <p>WHERE 子句由 MP 拼: {@code id = ?} + 逻辑删除条件 {@code deleted = 0} +
     * 租户条件 {@code tenant_id = ?} (多租户拦截器只加 WHERE, 不加 SET) +
     * 乐观锁条件 {@code version = ?} (取的是 {@code MP_OPTLOCK_VERSION_ORIGINAL}, 即实体上的旧版本值)。
     *
     * <p><b>聚合回写也带乐观锁</b> —— {@code agg} 上带 {@code version}, MP 因此在 SET 里写
     * {@code version = <新值>} (新值 = 旧版本 + 1, 由
     * {@code OptimisticLockerInnerInterceptor.doOptimisticLocker} 算出后写回实体),
     * 在 WHERE 里写 {@code version = <旧值>}; 命中 0 行说明
     * 期间被别人改过 → 抛 {@link OrderErrorCode#ORDER_CONFLICT} (整个事务回滚)。
     * {@code version} 取 {@code order.getVersion()} —— 编辑路径里头部 UPDATE 已把版本顶到 v+1
     * (MP 会把新版本写回实体), 建单路径里 insert 之后实体上的 version 与库里一致 (都是 0)。
     */
    private void replaceChildren(Order order, List<CargoRequest> cargo, List<ServiceItemRequest> items,
                                 CargoAggregate summary) {
        // 聚合 (summary) 由调用方算好传进来 —— 调用方在**任何写库之前**就算,
        // 这样「合计溢出」和「单行越界」是同一口径: 失败请求一行都不碰数据库。
        Long orderId = order.getId();
        cargoMapper.delete(new LambdaQueryWrapper<OrderCargo>().eq(OrderCargo::getOrderId, orderId));
        serviceItemMapper.delete(
                new LambdaQueryWrapper<OrderServiceItem>().eq(OrderServiceItem::getOrderId, orderId));

        for (CargoRequest r : cargo) {
            OrderCargo row = new OrderCargo();
            row.setOrderId(orderId);
            row.setCargoNameCn(r.cargoNameCn());
            row.setCargoNameEn(r.cargoNameEn());
            row.setHsCode(r.hsCode());
            row.setPkgType(r.pkgType());
            row.setPkgQty(nz(r.pkgQty(), SCALE_3, "pkg_qty"));
            row.setUnit(r.unit());
            row.setWeightKg(nz(r.weightKg(), SCALE_3, "weight_kg"));
            row.setNetWeightKg(opt(r.netWeightKg(), SCALE_3, "net_weight_kg"));
            row.setVolumeCbm(nz(r.volumeCbm(), SCALE_3, "volume_cbm"));
            row.setLengthCm(opt(r.lengthCm(), SCALE_3, "length_cm"));
            row.setWidthCm(opt(r.widthCm(), SCALE_3, "width_cm"));
            row.setHeightCm(opt(r.heightCm(), SCALE_3, "height_cm"));
            row.setIsStackable(r.isStackable() == null ? 1 : (r.isStackable() ? 1 : 0));
            row.setCargoValue(opt(r.cargoValue(), SCALE_2, "cargo_value"));
            row.setCurrencyCode(r.currencyCode());
            row.setBrandName(r.brandName());
            row.setModel(r.model());
            row.setIsDangerous(Boolean.TRUE.equals(r.isDangerous()) ? 1 : 0);
            row.setUnNo(r.unNo());
            row.setDangerClass(r.dangerClass());
            row.setRemark(r.remark());
            cargoMapper.insert(row);
        }

        for (ServiceItemRequest r : items) {
            OrderServiceItem row = new OrderServiceItem();
            row.setOrderId(orderId);
            row.setFeeType(r.feeType());
            row.setFeeName(r.feeName());
            row.setFeeCategory(r.feeCategory());
            row.setCalcBasis(r.calcBasis());
            row.setQty(nz(r.qty(), SCALE_3, "qty"));
            row.setUnitPrice(nz(r.unitPrice(), SCALE_4, "unit_price"));
            row.setAmount(nz(r.amount(), SCALE_2, "amount"));
            row.setCurrencyCode(r.currencyCode() == null || r.currencyCode().isBlank()
                    ? "CNY" : r.currencyCode());
            row.setSupplierId(r.supplierId());
            row.setCostAmount(opt(r.costAmount(), SCALE_2, "cost_amount"));
            row.setIsMandatory(Boolean.TRUE.equals(r.isMandatory()) ? 1 : 0);
            row.setSource(OrderConstants.SRC_MANUAL);
            row.setRemark(r.remark());
            serviceItemMapper.insert(row);
        }

        Order agg = new Order();
        agg.setId(orderId);
        agg.setTotalQty(summary.totalQty());
        agg.setTotalWeightKg(summary.totalWeightKg());
        agg.setTotalVolumeCbm(summary.totalVolumeCbm());
        agg.setIsDangerous(summary.anyDangerous() ? 1 : 0);
        agg.setVersion(order.getVersion());
        int rows = orderMapper.updateById(agg);
        if (rows == 0) {
            throw new OrderException(OrderErrorCode.ORDER_CONFLICT,
                    "委托单已被他人修改, 请刷新后重试");
        }

        order.setTotalQty(summary.totalQty());
        order.setTotalWeightKg(summary.totalWeightKg());
        order.setTotalVolumeCbm(summary.totalVolumeCbm());
        order.setIsDangerous(summary.anyDangerous() ? 1 : 0);
    }

    /** 货物明细的聚合结果。 */
    private record CargoAggregate(BigDecimal totalQty, BigDecimal totalWeightKg,
                                  BigDecimal totalVolumeCbm, boolean anyDangerous) {}

    /**
     * 算明细的聚合值 —— **在动数据库之前**算, 合计溢出当场抛。
     *
     * <p><b>为什么单独拎出来</b> —— 三个合计列本身也是 {@code DECIMAL(18,3)}, 多行相加
     * 一样可能撑爆 (例如两行 {@code pkg_qty = 999999999999999.9} 相加就超了)。
     * 放在明细 INSERT 之后才发现, 就要靠事务回滚来收拾; 放在这里, 失败请求**一行都不碰数据库**,
     * 与 {@link #requireNumerics} 是同一口径。
     */
    private static CargoAggregate aggregateCargo(List<CargoRequest> cargo) {
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal totalVolume = BigDecimal.ZERO;
        boolean anyDangerous = false;

        for (CargoRequest r : cargo) {
            totalQty = totalQty.add(nz(r.pkgQty(), SCALE_3, "pkg_qty"));
            totalWeight = totalWeight.add(nz(r.weightKg(), SCALE_3, "weight_kg"));
            totalVolume = totalVolume.add(nz(r.volumeCbm(), SCALE_3, "volume_cbm"));
            anyDangerous = anyDangerous || Boolean.TRUE.equals(r.isDangerous());
        }

        requireWithinUpperBound(totalQty, SCALE_3, "total_qty");
        requireWithinUpperBound(totalWeight, SCALE_3, "total_weight_kg");
        requireWithinUpperBound(totalVolume, SCALE_3, "total_volume_cbm");

        return new CargoAggregate(totalQty, totalWeight, totalVolume, anyDangerous);
    }

    private Order requireOrder(Long id) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw new OrderException(OrderErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    // ------------------------------------------------------------ 状态转换的守卫与收口

    /**
     * 状态合法性判定 —— **必须走 {@code StateMachineEngine}**, 不看 Java 常量表。
     *
     * <p>用 {@code allowedNext} 先判 (而不是直接调 {@code assertTransition}), 因为
     * {@code assertTransition} 抛的是 {@code StateMachineException} —— code 14001 / HTTP **400**,
     * 与本轮约定的 409 / 409_101 不符。收口在这里统一转换, 客户端拿到的就是 409。
     *
     * <p><b>{@code OrderConstants.TRANSITIONS} 不参与判定</b> —— 那张表只是
     * {@code t_state_transition} 的 Java 侧镜像, 用途是取动作名与 bootstrap 逐行比对;
     * 合法性以库里的那张表为准。
     */
    private void requireTransition(Order order, String toState, String action) {
        List<String> allowed = allowedNextStates(order.getOrderStatus());
        if (!allowed.contains(toState)) {
            throw new OrderException(OrderErrorCode.STATE_TRANSITION_INVALID,
                    "当前状态 " + order.getOrderStatus() + " 不允许执行动作 " + action
                            + " (目标状态 " + toState + ")");
        }
    }

    /**
     * 当前状态的下一步可去状态 —— 走 {@code StateMachineEngine.allowedNext}。
     *
     * <p>引擎的返回值不会是 null (它内部是 {@code stream().toList()}), 这里做一次 null 兜底,
     * 免得将来引擎换了实现之后, 一个 null 变成 NPE → HTTP 500。
     */
    private List<String> allowedNextStates(String fromState) {
        List<String> allowed = stateMachineEngine.allowedNext(
                OrderConstants.STATE_MACHINE_CODE, fromState);
        return allowed == null ? List.of() : allowed;
    }

    /**
     * 落状态 + 发事件 + 返回详情 —— 所有转换的唯一出口。
     *
     * <p><b>状态 UPDATE 命中 0 行 = 乐观锁冲突 → 抛 ORDER_CONFLICT (409_103),
     * 而且必须在发事件之前抛</b> —— 冲突时不能发出一条「其实没发生」的转换事件
     * (否则 finance 会照着一条不存在的转换去计费)。
     * 判定依据: 实体带 {@code @Version} → MP 生成
     * {@code SET ..., version = <新值> WHERE id = ? AND version = <旧值>} → 行必然变化
     * → MySQL affected rows 必为 1 → <b>命中 0 行只可能是版本不匹配</b>, 不是「值没变」。
     * (MP 的 {@code OptimisticLockerInnerInterceptor.doOptimisticLocker} 算出的新版本是
     * <b>参数</b> —— {@code updatedVersionVal = old + 1}, 写回实体后由 {@code SET} 取到;
     * 它生成的不是字面量 {@code version = version + 1}。)
     * 与 {@code OrderServiceImpl.update()} / {@code replaceChildren()} 的既有写法同款。
     *
     * <p><b>转换原因 ({@code reason}) 进事件流</b> ({@code getReason()}) ——
     * 取消 / 作废 / 关账的原因同时落在订单行的
     * {@code cancel_reason} / {@code void_reason} / {@code close_reason} 三列 (V42 都是 VARCHAR(255));
     * <b>退回 (return) 的原因只存在于事件里</b> —— V42 的 {@code t_order} 没有
     * {@code return_reason} 列, 本轮不为此加列 (加列要动迁移脚本, 超出本轮范围)。
     */
    private OrderDetailView applyTransition(Order order, String toState, String action, String reason) {
        String from = order.getOrderStatus();
        order.setOrderStatus(toState);
        int rows = orderMapper.updateById(order);
        if (rows == 0) {
            throw new OrderException(OrderErrorCode.ORDER_CONFLICT,
                    "委托单已被他人修改, 请刷新后重试");
        }

        eventBus.publish(new OrderStateChangedEvent(
                TenantContext.require(), order.getId(), order.getOrderNo(),
                from, toState, action, reason, order.getCustomerId(),
                order.getTotalWeightKg(), order.getTotalVolumeCbm(), order.getTotalQty(),
                estimatedAmount(order.getId()), order.getSettlementCurrency(), null));

        return toDetail(order);
    }

    /**
     * 转换原因守卫 —— 长度区间 5..255 字。
     *
     * <p><b>三档分开判, 一档一条码</b> —— 空白 → {@link OrderErrorCode#REASON_REQUIRED}
     * (400_103, 「原因不能为空」); 非空白但短于 5 字 → {@link OrderErrorCode#REASON_TOO_SHORT}
     * (400_108); 超过 255 字 → {@link OrderErrorCode#REASON_TOO_LONG} (400_107)。
     * <b>「太短」与「为空」必须分开</b> —— 客户填了 3 个字却被告知「原因不能为空」是骗人;
     * 本仓一贯「一个失败一条码」, 客户端不该靠猜来区分这两种失败。
     *
     * <p><b>上限 255 字是 DDL 定的</b> —— V42 的 {@code cancel_reason} / {@code close_reason} /
     * {@code void_reason} 三列都是 {@code VARCHAR(255)}。不校验上限的话, 客户端传 300 字
     * → MySQL 1406 Data too long → {@code DataIntegrityViolationException}
     * → {@code GlobalExceptionHandler} 没有对应分支 → 落 catch-all → <b>HTTP 500</b>。
     * 在 service 层挡掉才能给客户端一个正经的 400。
     *
     * <p><b>上限按「原串长度」判, 下限按「trim 后的长度」判</b> —— 落进
     * {@code VARCHAR(255)} 的是原串 (含首尾空白), 所以上限必须看原串;
     * 而「说了几句正事」看的是 trim 后的内容长度。只判 trim 后的长度的话,
     * 「255 个正文字 + 首尾空白」的串能过守卫, 却照样撞 MySQL 1406 → 500。
     * (VARCHAR 的长度按**字符**算, 而 {@code String.length()} 按 UTF-16 码元算 ——
     * 遇到 BMP 以外的字符 (emoji 等) 本守卫会偏保守, 宁可给 400 也不放行长串。)
     */
    private void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new OrderException(OrderErrorCode.REASON_REQUIRED);
        }
        if (reason.trim().length() < OrderConstants.REASON_MIN_LENGTH) {
            throw new OrderException(OrderErrorCode.REASON_TOO_SHORT);
        }
        if (reason.length() > OrderConstants.REASON_MAX_LENGTH) {
            throw new OrderException(OrderErrorCode.REASON_TOO_LONG);
        }
    }

    /**
     * submit 守卫: 必填项齐 + 至少 1 条明细 + 至少 1 条服务项。
     *
     * <p><b>码位分开用, 不复用 {@link OrderErrorCode#CARGO_EMPTY}</b> ——
     * {@code CARGO_EMPTY} 的消息是「货物明细不能为空」, 说的是**货物明细**;
     * 「头部 4 个枚举缺了一个」是另一回事, 用它表达会让客户端把「枚举没填」
     * 误当成「没传货物明细」。枚举缺失走 {@link OrderErrorCode#ENUM_VALUE_INVALID} (400_106)
     * —— 与 {@code requireEnums} 里「枚举取值为空 / 取值不在受控词表内」同一码位,
     * 客户端不用区分「空」和「非法」; 归属缺失走
     * {@link OrderErrorCode#CUSTOMER_NOT_FOUND} (404_102) —— 与 {@link #getOwned}
     * 的「缺少客户身份」同一码位。
     * 明细为空仍然用 {@code CARGO_EMPTY} / {@link OrderErrorCode#SERVICE_ITEM_EMPTY}
     * —— 这两个码的语义本来就对得上。
     *
     * <p><b>「枚举缺失」这道闸是防御性的</b> —— V42 里 {@code business_type} /
     * {@code trade_direction} / {@code incoterms} / {@code service_type} 四列都是
     * {@code NOT NULL}, 从库里读出来的行不该缺它们; 真缺了说明有脏数据或有人绕过
     * service 直接写库, 这里挡一下总比让它带着 null 进流程好。
     */
    private void requireBasicsComplete(Order o) {
        if (o.getCustomerId() == null) {
            throw new OrderException(OrderErrorCode.CUSTOMER_NOT_FOUND, "缺少客户身份");
        }
        if (o.getBusinessType() == null || o.getTradeDirection() == null
                || o.getIncoterms() == null || o.getServiceType() == null) {
            throw new OrderException(OrderErrorCode.ENUM_VALUE_INVALID,
                    "必填项缺失, 不能提交 (businessType / tradeDirection / incoterms / serviceType)");
        }
        // selectCount 的返回类型是 Long —— MP 命中 0 行时给 0L 而不是 null;
        // 这里仍做 null 兜底, 免得解引用 NPE。
        Long cargoCount = cargoMapper.selectCount(
                new LambdaQueryWrapper<OrderCargo>().eq(OrderCargo::getOrderId, o.getId()));
        if (cargoCount == null || cargoCount == 0L) {
            throw new OrderException(OrderErrorCode.CARGO_EMPTY);
        }
        Long itemCount = serviceItemMapper.selectCount(
                new LambdaQueryWrapper<OrderServiceItem>().eq(OrderServiceItem::getOrderId, o.getId()));
        if (itemCount == null || itemCount == 0L) {
            throw new OrderException(OrderErrorCode.SERVICE_ITEM_EMPTY);
        }
    }

    /** confirm 守卫: 服务项金额合计 > 0。 */
    private void requirePositiveAmount(Order o) {
        BigDecimal total = estimatedAmount(o.getId());
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            throw new OrderException(OrderErrorCode.AMOUNT_INVALID);
        }
    }

    /**
     * 服务项金额合计 —— 确认守卫与事件里的 {@code estimatedAmount} 用的是**同一个口径**。
     *
     * <p>amount 为 null 的行按 0 算 (列可空, 合计口径与落库的 {@code DECIMAL(18,2)} 一致)。
     */
    private BigDecimal estimatedAmount(Long orderId) {
        return serviceItemMapper.selectList(
                        new LambdaQueryWrapper<OrderServiceItem>()
                                .eq(OrderServiceItem::getOrderId, orderId))
                .stream()
                .map(OrderServiceItem::getAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 门户端的归属校验 —— 与 {@link #getOwned} 同一口径。
     *
     * <p><b>customerId 为 null 必须先挡</b> —— 下面的 {@code customerId.equals(...)}
     * 会 NPE → {@code GlobalExceptionHandler} 的 catch-all → <b>HTTP 500</b>。
     * service 层不能假设调用方 (controller) 一定守规矩, 所以这里跟 {@code getOwned}
     * 一样抛 {@link OrderErrorCode#CUSTOMER_NOT_FOUND} (404_102)「缺少客户身份」。
     *
     * <p><b>归属不符按「不存在」处理</b> —— 不能泄露「存在但不属于你」。
     */
    private Order requireOwned(Long id, Long customerId) {
        if (customerId == null) {
            throw new OrderException(OrderErrorCode.CUSTOMER_NOT_FOUND, "缺少客户身份");
        }
        Order order = orderMapper.selectById(id);
        if (order == null || !customerId.equals(order.getCustomerId())) {
            throw new OrderException(OrderErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    private OrderView toView(Order o) {
        return new OrderView(
                o.getId(), o.getOrderNo(), o.getCustomerId(), o.getBusinessType(),
                o.getTradeDirection(), o.getIncoterms(), o.getServiceType(),
                o.getOriginAddress(), o.getDestAddress(),
                o.getTotalQty(), o.getTotalWeightKg(), o.getTotalVolumeCbm(),
                o.getOrderStatus(), o.getSettlementCurrency(),
                o.getEtd(), o.getEta(), o.getCreatedAt());
    }

    private OrderDetailView toDetail(Order o) {
        List<OrderCargo> cargoRows = cargoMapper.selectList(
                new LambdaQueryWrapper<OrderCargo>().eq(OrderCargo::getOrderId, o.getId()));
        List<OrderServiceItem> itemRows = serviceItemMapper.selectList(
                new LambdaQueryWrapper<OrderServiceItem>().eq(OrderServiceItem::getOrderId, o.getId()));

        List<CargoView> cargoViews = new ArrayList<>();
        for (OrderCargo c : cargoRows) {
            cargoViews.add(new CargoView(c.getId(), c.getCargoNameCn(), c.getCargoNameEn(),
                    c.getHsCode(), c.getPkgType(), c.getPkgQty(), c.getUnit(), c.getWeightKg(),
                    c.getNetWeightKg(), c.getVolumeCbm(), c.getLengthCm(), c.getWidthCm(),
                    c.getHeightCm(), c.getIsStackable(), c.getCargoValue(), c.getCurrencyCode(),
                    c.getBrandName(), c.getModel(), c.getIsDangerous(), c.getUnNo(),
                    c.getDangerClass(), c.getRemark()));
        }
        List<ServiceItemView> itemViews = new ArrayList<>();
        for (OrderServiceItem s : itemRows) {
            itemViews.add(new ServiceItemView(s.getId(), s.getFeeType(), s.getFeeName(),
                    s.getFeeCategory(), s.getCalcBasis(), s.getQty(), s.getUnitPrice(),
                    s.getAmount(), s.getCurrencyCode(), s.getSupplierId(), s.getCostAmount(),
                    s.getIsMandatory(), s.getSource(), s.getRemark()));
        }

        return new OrderDetailView(
                o.getId(), o.getOrderNo(), o.getCustomerId(), o.getBusinessType(),
                o.getTradeDirection(), o.getIncoterms(), o.getServiceType(),
                o.getOriginAddress(), o.getDestAddress(),
                o.getTotalQty(), o.getTotalWeightKg(), o.getTotalVolumeCbm(),
                o.getOrderStatus(), o.getSettlementCurrency(),
                o.getEtd(), o.getEta(), o.getCreatedAt(),
                o.getCargoReadyDate(), o.getContainerQty(), o.getContainerTypeCode(),
                o.getMarksAndNos(), o.getCargoDescription(), o.getIsDangerous(),
                o.getIsOversized(), o.getTemperatureRange(), o.getRemark(),
                o.getCancelReason(), o.getCloseReason(), o.getVoidReason(),
                o.getSubmittedAt(), o.getConfirmedAt(), o.getDeliveredAt(),
                o.getSettledAt(), o.getClosedAt(), o.getCreatedBy(), o.getUpdatedBy(),
                cargoViews, itemViews, allowedNextStates(o.getOrderStatus()));
    }

    /**
     * NOT NULL 的数量/金额列 —— null 落 0, 非 null 截到列的 scale 并检查装不装得下。
     *
     * <p><b>必须截位</b> —— MySQL 对超出 DECIMAL scale 的小数是**静默舍入** (不报错),
     * 不显式 setScale 的话 12.12345 会悄悄变成 12.123。
     *
     * <p><b>越界必须在这里拦, 不能让它撞 MySQL</b> —— 客户端传 {@code 1e15} 超出
     * {@code DECIMAL(18,3)}, MySQL 报 1264 out of range → {@code DataIntegrityViolationException}
     * → {@code GlobalExceptionHandler} 没有对应分支 → HTTP 500; 传 {@code 1e999} 炸得更早:
     * Jackson 把它解析成 {@code Double.POSITIVE_INFINITY}, {@code BigDecimal.valueOf} 抛
     * {@code NumberFormatException} → 也是 500。两种都在这里变成 400 (NUMERIC_OUT_OF_RANGE)。
     */
    private static BigDecimal nz(Double v, int scale, String column) {
        return v == null ? BigDecimal.ZERO.setScale(scale) : toColumnValue(v, scale, column);
    }

    /**
     * 可空的金额/尺寸列 —— null 保持 null (不把「没填」变成 0), 非 null 截到列的 scale。
     */
    private static BigDecimal opt(Double v, int scale, String column) {
        return v == null ? null : toColumnValue(v, scale, column);
    }

    /** double → 列值: 先挡非有限数, 再按列 scale 截位, 再挡负数, 最后查越界。 */
    private static BigDecimal toColumnValue(double v, int scale, String column) {
        if (!Double.isFinite(v)) {
            throw new OrderException(OrderErrorCode.NUMERIC_OUT_OF_RANGE,
                    column + " 数值非法 (不是有限数): " + v);
        }
        BigDecimal value = BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
        // 负数在这里再挡一道 —— DTO 上的 @PositiveOrZero 已经挡过一次, 但 service 不该
        // 只依赖 DTO: 门户端 DTO 是 Task 7 新写的, 内部调用更是直接绕过校验器。
        if (value.signum() < 0) {
            throw new OrderException(OrderErrorCode.NUMERIC_OUT_OF_RANGE,
                    column + " 不能为负数: " + value.toPlainString());
        }
        requireWithinUpperBound(value, scale, column);
        return value;
    }

    /**
     * 检查一个值**装不装得进** {@code DECIMAL(18, scale)} —— 整数位 {@code 18 - scale} 位。
     *
     * <p>比较用**截位之后**的值 —— MySQL 存的是截位/舍入后的数, 越界判定必须跟它一致。
     *
     * <p><b>只查上界, 不查负数</b> —— 负数在入口 {@link #toColumnValue} 就被挡了
     * (那里才是「单值」的唯一入口); 这个方法负责的是「列的容量」这回事。
     * 早先这里用的是 {@code value.abs()} 比较, 于是负数会被放过, 而方法名
     * {@code requireInRange} 又让人以为它管的是区间 —— 名实不符, 下一个人照着名字
     * 加一条不经 {@code toColumnValue} 的调用就会踩坑。现在改成**只查上界**,
     * 名字也跟着改成 {@code requireWithinUpperBound}, 职责单一。
     */
    private static void requireWithinUpperBound(BigDecimal value, int scale, String column) {
        if (value == null) {
            return;
        }
        if (value.compareTo(maxOfScale(scale)) > 0) {
            throw new OrderException(OrderErrorCode.NUMERIC_OUT_OF_RANGE,
                    column + " 超出列宽 DECIMAL(18," + scale + ") 的范围: " + value.toPlainString());
        }
    }

    /** {@code DECIMAL(18, scale)} 的最大绝对值 —— 整数位 18-scale 位全 9, 小数位全 9。 */
    private static BigDecimal maxOfScale(int scale) {
        return switch (scale) {
            case 2 -> MAX_18_2;
            case 3 -> MAX_18_3;
            case 4 -> MAX_18_4;
            default -> throw new IllegalArgumentException("未登记的列 scale: " + scale);
        };
    }
}
