package com.lumen.customer.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.customer.dto.PortalCancelFreightOrderRequest;
import com.lumen.customer.dto.PortalFreightOrderRequest;
import com.lumen.customer.web.PortalIdentity;
import com.lumen.order.OrderConstants;
import com.lumen.order.dto.CreateOrderRequest;
import com.lumen.order.dto.OrderDetailView;
import com.lumen.order.dto.OrderView;
import com.lumen.order.dto.ServiceItemRequest;
import com.lumen.order.dto.ServiceItemView;
import com.lumen.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户门户货运委托单 controller (Iteration 4.6).
 *
 * <p>路由前缀 {@code /api/customer/freight-order} —— 与既有城配下单
 * ({@code /api/customer/order}) <b>完全分开</b>, 两者互不影响。
 * {@code CustomerContextFilter} 按 URL 前缀 {@code /api/customer/} 判定门户路由,
 * 所以这个前缀天然受门户鉴权保护。
 *
 * <p>5 个端点:
 * <ol>
 *   <li>GET  {@code /}                — {@code customer:freight_order:view}   — 我的委托单列表</li>
 *   <li>GET  {@code /{id}}            — {@code customer:freight_order:view}   — 详情 (跨客户 404)</li>
 *   <li>POST {@code /}                — {@code customer:freight_order:create} — 建单 (落 DRAFT)</li>
 *   <li>POST {@code /{id}/confirm}    — {@code customer:freight_order:confirm} — PENDING_CONFIRM → CONFIRMED</li>
 *   <li>POST {@code /{id}/cancel}     — {@code customer:freight_order:cancel}  — DRAFT / PENDING_CONFIRM → CANCELLED</li>
 * </ol>
 *
 * <p><b>客户能自助撤单的状态只有 {@code DRAFT} / {@code PENDING_CONFIRM}</b> ——
 * 依据是 V42 的 {@code t_state_transition} 的 guard 分档:
 * {@code DRAFT → CANCELLED} / {@code PENDING_CONFIRM → CANCELLED} 的
 * {@code guard_expression} 是 {@code role:order_sales} (销售受理前), 而
 * {@code CONFIRMED → CANCELLED} / {@code IN_OPERATION → CANCELLED} 是
 * {@code role:order_supervisor} —— 那是**我方主管**的撤单动作 (已确认 = 合同已成立),
 * 门户端不开放。已确认及以后的撤单走管理端
 * {@code POST /api/v1/orders/{id}/cancel} ({@code order:cancel})。
 * 状态闸实现在 {@code OrderServiceImpl.requirePortalCancelable}。
 *
 * <p><b>这两个状态集合只在 order 域定义一次</b> —— {@link OrderConstants#PORTAL_CANCELABLE_STATES}
 * / {@link OrderConstants#PORTAL_CONFIRMABLE_STATES}; 放行闸与按钮可见性都从它派生。
 *
 * <p><b>归属边界</b>: customerId 一律来自 {@link PortalIdentity#requireCounterpartyId()},
 * <b>绝不从 body / query 读</b>。跨客户访问一律 404 (不泄露存在性)。
 *
 * <p><b>异常</b>: {@code OrderException extends BizException} → 由
 * {@code GlobalExceptionHandler.handleBiz} 翻译成 404 / 409 / 400。
 * <b>本类不新建 advice</b> (spec §六·3.1)。
 *
 * <p><b>{@code /cancel} 的 body 可缺省</b> —— {@code @RequestBody(required = false)}:
 * 客户端不带 body 时必须能进到方法体 (把 reason 传 null 给 service),
 * 由 service 的 {@code requireReason} 抛 {@code REASON_REQUIRED} (400_103) /
 * {@code REASON_TOO_SHORT} (400_108)。
 * 门户模块比管理端轻 —— {@code CustomerExceptionHandler} 有
 * {@code HttpMessageNotReadableException} 分支 (返回 400 而不是 500),
 * 但错误信息会变成「参数解析失败」而不是「原因不能为空」, 语义不对。
 *
 * <p><b>内部成本双向都堵</b> —— 写入侧建单时把服务项的 {@code supplierId} /
 * {@code costAmount} 强制置 null; 读出侧 {@code detail} / {@code confirm} / {@code cancel}
 * 三个端点返回详情前再裁一次 (见 private {@code toPortalView(OrderDetailView)})。
 * 门户是对外端点, 我方采购成本与供应商名单**不能**返给客户。
 */
@RestController
@RequestMapping("/api/customer/freight-order")
@RequiredArgsConstructor
public class PortalFreightOrderController {

    private final OrderService orderService;

    // ================================================================
    //  1. GET / — 我的委托单列表
    // ================================================================

    @GetMapping
    @PreAuthorize("hasAuthority('customer:freight_order:view')")
    public R<PageResult<OrderView>> list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status) {
        Long customerId = PortalIdentity.requireCounterpartyId();
        return R.ok(orderService.pageByCustomer(customerId, current, size, status));
    }

    // ================================================================
    //  2. GET /{id} — 详情
    // ================================================================

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('customer:freight_order:view')")
    public R<OrderDetailView> detail(@PathVariable Long id) {
        Long customerId = PortalIdentity.requireCounterpartyId();
        return R.ok(toPortalView(orderService.getOwned(id, customerId)));
    }

    // ================================================================
    //  3. POST / — 建单 (DRAFT)
    // ================================================================

    @PostMapping
    @PreAuthorize("hasAuthority('customer:freight_order:create')")
    public R<OrderView> create(@Valid @RequestBody PortalFreightOrderRequest req) {
        Long customerId = PortalIdentity.requireCounterpartyId();
        return R.ok(orderService.createForCustomer(customerId, toCreateRequest(customerId, req)));
    }

    // ================================================================
    //  4. POST /{id}/confirm — 客户确认
    // ================================================================

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('customer:freight_order:confirm')")
    public R<OrderDetailView> confirm(@PathVariable Long id) {
        Long customerId = PortalIdentity.requireCounterpartyId();
        return R.ok(toPortalView(orderService.confirmOwned(id, customerId)));
    }

    // ================================================================
    //  5. POST /{id}/cancel — 客户取消
    // ================================================================

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('customer:freight_order:cancel')")
    public R<OrderDetailView> cancel(@PathVariable Long id,
                                     @RequestBody(required = false) PortalCancelFreightOrderRequest body) {
        Long customerId = PortalIdentity.requireCounterpartyId();
        return R.ok(toPortalView(
                orderService.cancelOwned(id, customerId, body == null ? null : body.reason())));
    }

    // ================================================================
    //  private helpers
    // ================================================================

    /**
     * 门户 DTO → 管理端 DTO。
     *
     * <p><b>customerId 用服务端取到的那个</b> —— body 里没有这个字段, 客户端也没法指定归属。
     * {@code OrderService.createForCustomer} 里还会再强制覆盖一次 (双保险)。
     *
     * <p><b>内部成本字段被抹掉</b>: {@code costAmount} (我方成本) 与 {@code supplierId}
     * (供应商) 强制置 null —— 门户端客户**不应该**能写入我方内部成本。
     */
    private CreateOrderRequest toCreateRequest(Long customerId, PortalFreightOrderRequest req) {
        List<ServiceItemRequest> items = req.serviceItems() == null
                ? List.of()
                : req.serviceItems().stream().map(this::stripInternalCost).toList();
        return new CreateOrderRequest(
                customerId,
                req.businessType(),
                req.tradeDirection(),
                req.incoterms(),
                req.serviceType(),
                req.originAddress(),
                req.destAddress(),
                req.cargoReadyDate(),
                req.etd(),
                req.eta(),
                req.containerQty(),
                req.containerTypeCode(),
                req.marksAndNos(),
                req.cargoDescription(),
                req.isOversized(),
                req.temperatureRange(),
                req.settlementCurrency(),
                req.remark(),
                req.cargo(),
                items);
    }

    /**
     * 抹掉服务项里的内部成本字段 —— <b>只处理写入侧</b>;
     * 读出侧的裁剪见下面的 {@code toPortalView(OrderDetailView)}。
     */
    private ServiceItemRequest stripInternalCost(ServiceItemRequest r) {
        return new ServiceItemRequest(
                r.feeType(), r.feeName(), r.feeCategory(), r.calcBasis(),
                r.qty(), r.unitPrice(), r.amount(), r.currencyCode(),
                null, null, r.isMandatory(), r.remark());
    }

    /**
     * <b>门户视图的收口</b> —— 做两件事: 抹掉服务项里的内部成本, 收窄 allowedNextStates。
     *
     * <p><b>第一件: 读出侧的内部成本裁剪</b> —— 抹掉服务项里的 {@code supplierId} (我方供应商)
     * 与 {@code costAmount} (我方成本额), 其余 38 个组件原样保留。
     *
     * <p><b>为什么门户详情端点必须走这一步</b>: {@code OrderDetailView} 与管理端
     * {@code com.lumen.order.controller.OrderController#get} 返回的是<b>同一个</b>视图,
     * 它的 {@code serviceItems} 带 {@code supplierId} / {@code costAmount} —— 直接返给
     * 客户就是我方采购成本与供应商名单泄露。写入侧早就堵了 (建单强制置 null),
     * 读出侧却是开的 —— <b>半堵比不堵更糟</b>: 门户自建的单看不到成本,
     * 内部建的单客户一点就看到。所以本轮把读出侧一并堵上。
     *
     * <p><b>三个端点共用这一条路径</b> —— {@code detail} / {@code confirm} / {@code cancel}
     * 返回的都是 {@code OrderDetailView}, 漏掉哪一个就漏一个口子。
     *
     * <p><b>第二件: 收窄 allowedNextStates</b> —— 见 {@link #portalNextStates(String)}。
     *
     * <p>列表端点不需要这一步 —— 它返回的是 {@code OrderView}, 压根没有成本字段。
     */
    private OrderDetailView toPortalView(OrderDetailView detail) {
        List<ServiceItemView> items = detail.serviceItems() == null
                ? null
                : detail.serviceItems().stream().map(ServiceItemView::withoutInternalCost).toList();
        return detail.withServiceItems(items)
                .withAllowedNextStates(portalNextStates(detail.orderStatus()));
    }

    /**
     * 门户能触发的下一态 —— 只认「客户自己点得到的」那两个动作。
     *
     * <p><b>为什么不能直接用 {@code StateMachineEngine.allowedNext}</b> ——
     * 那是整张状态机的出边, 含客户永远触发不了的内部动作 (VOID / IN_OPERATION /
     * SETTLED / CLOSED); 而且 {@code CONFIRMED} 的出边里含 {@code CANCELLED}
     * (主管撤单), 直接下发会让前端在已确认的单上渲染出「取消委托」按钮, 客户一点必撞 409。
     *
     * <p><b>状态集合不在这里写死</b> —— 取 order 域的
     * {@link OrderConstants#PORTAL_CANCELABLE_STATES} / {@link OrderConstants#PORTAL_CONFIRMABLE_STATES}:
     * 门户端按钮可见性 (本方法) 与门户端放行闸
     * ({@code OrderServiceImpl.requirePortalCancelable}) 必须同口径, 否则会出现
     * 「前端亮出取消按钮、点下去 409」或「能撤却不给按钮」。
     */
    private List<String> portalNextStates(String orderStatus) {
        List<String> next = new ArrayList<>();
        if (OrderConstants.PORTAL_CONFIRMABLE_STATES.contains(orderStatus)) {
            next.add(OrderConstants.STATUS_CONFIRMED);
        }
        if (OrderConstants.PORTAL_CANCELABLE_STATES.contains(orderStatus)) {
            next.add(OrderConstants.STATUS_CANCELLED);
        }
        return next;
    }
}
