package com.lumen.order.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.order.dto.CreateOrderRequest;
import com.lumen.order.dto.OrderDetailView;
import com.lumen.order.dto.OrderView;
import com.lumen.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端货运委托单控制器 (Iteration 4.6).
 *
 * <p>路径前缀 {@code /api/v1/orders} —— 与既有管理端控制器一致
 * ({@code /api/v1/finance/accruals} / {@code /api/v1/dispatch/orders} / {@code /api/v1/wms/customers})。
 * 前端契约见 {@code lumen-admin-web/src/services/order/orderService.ts} —— 它的 axios
 * {@code baseURL} 已经是 {@code /api/v1}, 请求路径写的是相对形式 {@code /orders}。
 *
 * <p>分页参数名用 {@code current} / {@code size} —— 与 {@code CsExceptionController}
 * (以及 lumen-dispatch 的几个 controller) 一致, 也是前端 {@code orderService.page} 发的参数名。
 * (仓里另有 {@code pageNum}/{@code pageSize} 与 {@code page} 两套写法, 本端点跟前端契约走。)
 *
 * <p><b>{@code @RequestParam} 一律显式写 {@code name = "..."}</b> —— 不靠 Spring 的
 * 编译期参数名绑定 (父 pom 继承 {@code spring-boot-starter-parent:3.2.5}, 带
 * {@code -parameters}, 所以不写 name 也能绑定)。显式写 name 是为了让「HTTP 查询参数名」
 * 这份契约<b>写进注解里</b>, 而不是寄生在 Java 变量名上:
 * 有人把 {@code current} 改名成 {@code pageNum} (只改 Java 侧, 不动前端) 时,
 * 不写 name 的版本会让 Spring <b>找不到</b> {@code current} → <b>静默</b>落回
 * {@code defaultValue} —— 列表永远返回第 1 页 20 条, 用户点第 2 页没反应、也不报错;
 * 显式写了 name 的版本不受 Java 变量改名影响 (契约在注解上)。
 * <br>
 * <b>这条只锁「Java 侧改名」这一侧</b>: 反过来前端改了查询参数名, 仍是静默降级
 * ({@code current}/{@code size} 有 defaultValue → 落回第 1 页; 其余 4 个是可选筛选项
 * → 等价于「不筛选」), 那要靠前后端一起改。
 * {@code OrderControllerTest.page_bindsExplicitRequestParamNames} 把这 6 个 name 逐条锁死
 * (读的是<b>注解</b>上的 {@code name()}, 不是反射参数名 —— 后者会把「改了变量名」也放过)。
 *
 * <p>异常统一由 {@code GlobalExceptionHandler.handleBiz} 处理
 * ({@code OrderException extends BizException}), 本类不写 try/catch。
 *
 * <p><b>带 reason 的 4 个端点 (return / cancel / void / close) 的 body 可缺省</b> ——
 * 它们的 {@code @RequestBody} 必须写成 {@code required = false}。
 * {@code @RequestBody} 默认 {@code required = true}: 客户端<b>不带 body</b> 时,
 * Spring 在<b>参数解析阶段</b>就抛 {@code HttpMessageNotReadableException}
 * ("Required request body is missing"), <b>方法体根本进不去</b> ——
 * 代码里那层 {@code body == null ? null : body.get("reason")} 的兜底形同虚设。
 * 而 {@code GlobalExceptionHandler} 只声明了 {@code BizException} /
 * {@code MethodArgumentNotValidException} / {@code BindException} /
 * {@code AccessDeniedException} / {@code AuthenticationException} 五个分支,
 * <b>没有</b> {@code HttpMessageNotReadableException} 的分支 → 它落 catch-all → <b>HTTP 500</b>。
 * 正确行为是进到方法体 → service 的 {@code requireReason} 抛
 * {@code OrderException(REASON_REQUIRED)} → <b>HTTP 400</b>。
 * 仓里既有的同类端点都是这么写的 (例: {@code PortalCommonController} /
 * {@code PortalReconController} / {@code ApprovalController} / {@code BillingAccrualController} /
 * {@code BillingAdjustmentController} / {@code BillingInvoiceController} /
 * {@code NumberRuleController})。
 *
 * <p><b>reason 的长度校验不在本类, 在 service 层</b> ——
 * {@code OrderServiceImpl.requireReason}: 下限 {@code OrderConstants.REASON_MIN_LENGTH} (=5)
 * 按 trim 后的长度判, 上限 {@code OrderConstants.REASON_MAX_LENGTH} (=255) 按原串长度判
 * (落进 {@code VARCHAR(255)} 的是原串); 越界分别抛
 * {@code 400_103 REASON_REQUIRED} (空白) / {@code 400_108 REASON_TOO_SHORT} (短于 5 字) /
 * {@code 400_107 REASON_TOO_LONG} (超 255 字), 都是 HTTP 400。
 * 本类不再重复校验一遍 —— 两处校验会漂移。
 *
 * <p><b>create / update 用 {@code @Valid @RequestBody}</b> ——
 * {@code CreateOrderRequest} 的 {@code cargo} / {@code serviceItems} 组件上带 {@code @Valid},
 * 级联到 {@code CargoRequest} / {@code ServiceItemRequest} 的 jakarta 约束
 * (超长 / 负数 / 缺必填) → {@code MethodArgumentNotValidException}
 * → {@code GlobalExceptionHandler.handleValidation} → <b>HTTP 400 + PARAM_INVALID (4001)</b>。
 *
 * <p><b>{@code @PreAuthorize} 的权限码与 V42 seed 逐字一致</b> —— V42 的
 * {@code sys_permission} 里 seed 了 10 个管理端 {@code order:*} 码 ({@code order:view} /
 * {@code order:create} / {@code order:edit} / {@code order:submit} / {@code order:confirm} /
 * {@code order:cancel} / {@code order:void} / {@code order:operate} / {@code order:settle} /
 * {@code order:close})。抄错一个字母 → 那个端点永远 403, 所以
 * {@code OrderControllerTest} 把 13 个端点的路径与权限码一起锁死。
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService service;

    /**
     * GET /api/v1/orders — 分页列表。
     *
     * <p><b>查询参数名以 {@code @RequestParam(name = ...)} 为准</b> ——
     * {@code current} / {@code size} / {@code status} / {@code customerId} /
     * {@code orderNo} / {@code businessType}。这 6 个 name 与前端
     * {@code lumen-admin-web/src/services/order/orderService.ts} 的
     * {@code OrderPageQuery} 逐字对应, 且<b>写进了注解</b> ——
     * 改 Java 变量名不会改这份契约 (详见本类 javadoc 的「@RequestParam 一律显式写 name」段)。
     *
     * @param current      当前页 (1-based, 默认 1)
     * @param size         每页条数 (默认 20; 上限由 service 层收口)
     * @param status       可选, 按委托单状态过滤
     * @param customerId   可选, 按客户过滤
     * @param orderNo      可选, 按委托单号过滤
     * @param businessType 可选, 按运输方式过滤
     * @return {@code R<PageResult<OrderView>>}
     */
    @GetMapping
    @PreAuthorize("hasAuthority('order:view')")
    public R<PageResult<OrderView>> page(
            @RequestParam(name = "current", defaultValue = "1") long current,
            @RequestParam(name = "size", defaultValue = "20") long size,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "customerId", required = false) Long customerId,
            @RequestParam(name = "orderNo", required = false) String orderNo,
            @RequestParam(name = "businessType", required = false) String businessType) {
        return R.ok(service.page(current, size, status, customerId, orderNo, businessType));
    }

    /**
     * GET /api/v1/orders/{id} — 委托单详情。
     *
     * @return {@code R<OrderDetailView>}
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('order:view')")
    public R<OrderDetailView> get(@PathVariable Long id) {
        return R.ok(service.get(id));
    }

    /**
     * POST /api/v1/orders — 建单 (落 DRAFT)。
     *
     * @return {@code R<OrderView>}
     */
    @PostMapping
    @PreAuthorize("hasAuthority('order:create')")
    public R<OrderView> create(@Valid @RequestBody CreateOrderRequest request) {
        return R.ok(service.create(request));
    }

    /**
     * PUT /api/v1/orders/{id} — 编辑 (仅 DRAFT / PENDING_CONFIRM 可编辑)。
     *
     * <p><b>已知 API wart (本轮不修):</b> 编辑路径沿用 {@code CreateOrderRequest}
     * (建单与编辑共用同一个 DTO), 因此 body 里的 {@code customerId} 字段
     * <b>仍然是 {@code @NotNull} 必填</b> —— 不传会被 {@code @Valid} 挡成 400;
     * 但服务端在编辑路径<b>一律不动客户归属</b>: 传什么值都无效, 归属以库里原值为准。
     * 也就是说这份契约自相矛盾: <b>API 要求必填, 但服务端忽略它</b>。
     * 前端目前总是带上 {@code customerId}, 线上没有暴露。
     * 修法是拆 create / update 两个 DTO —— 那是接口改动, 超出本轮范围,
     * 留待后续切片处理。
     *
     * @return {@code R<OrderDetailView>}
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('order:edit')")
    public R<OrderDetailView> update(@PathVariable Long id,
                                      @Valid @RequestBody CreateOrderRequest request) {
        return R.ok(service.update(id, request));
    }

    /**
     * POST /api/v1/orders/{id}/submit — 提交待客户确认 (DRAFT → PENDING_CONFIRM)。
     *
     * @return {@code R<OrderDetailView>}
     */
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('order:submit')")
    public R<OrderDetailView> submit(@PathVariable Long id) {
        return R.ok(service.submit(id));
    }

    /**
     * POST /api/v1/orders/{id}/confirm — 确认 (PENDING_CONFIRM → CONFIRMED)。
     *
     * @return {@code R<OrderDetailView>}
     */
    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('order:confirm')")
    public R<OrderDetailView> confirm(@PathVariable Long id) {
        return R.ok(service.confirm(id));
    }

    /**
     * POST /api/v1/orders/{id}/return — 退回销售修改 (PENDING_CONFIRM → DRAFT)。
     *
     * <p>body 可缺省 ({@code required = false}); 缺省时 reason 传 null 给 service,
     * 由 {@code requireReason} 抛 {@code REASON_REQUIRED} (HTTP 400)。
     *
     * @return {@code R<OrderDetailView>}
     */
    @PostMapping("/{id}/return")
    @PreAuthorize("hasAuthority('order:submit')")
    public R<OrderDetailView> returnToDraft(@PathVariable Long id,
                                             @RequestBody(required = false) Map<String, String> body) {
        return R.ok(service.returnToDraft(id, body == null ? null : body.get("reason")));
    }

    /**
     * POST /api/v1/orders/{id}/cancel — 取消。
     *
     * <p>body 可缺省 ({@code required = false}); 缺省时 reason 传 null 给 service,
     * 由 {@code requireReason} 抛 {@code REASON_REQUIRED} (HTTP 400)。
     *
     * @return {@code R<OrderDetailView>}
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('order:cancel')")
    public R<OrderDetailView> cancel(@PathVariable Long id,
                                      @RequestBody(required = false) Map<String, String> body) {
        return R.ok(service.cancel(id, body == null ? null : body.get("reason")));
    }

    /**
     * POST /api/v1/orders/{id}/void — 作废。
     *
     * <p>方法名是 {@code voidOrder} 而不是 {@code void} —— {@code void} 是 Java 关键字,
     * 不能当方法名; 路径仍是 {@code /{id}/void}。
     *
     * <p>body 可缺省 ({@code required = false}); 缺省时 reason 传 null 给 service,
     * 由 {@code requireReason} 抛 {@code REASON_REQUIRED} (HTTP 400)。
     *
     * @return {@code R<OrderDetailView>}
     */
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('order:void')")
    public R<OrderDetailView> voidOrder(@PathVariable Long id,
                                         @RequestBody(required = false) Map<String, String> body) {
        return R.ok(service.voidOrder(id, body == null ? null : body.get("reason")));
    }

    /**
     * POST /api/v1/orders/{id}/start-operation — 开始操作执行 (CONFIRMED → IN_OPERATION)。
     *
     * @return {@code R<OrderDetailView>}
     */
    @PostMapping("/{id}/start-operation")
    @PreAuthorize("hasAuthority('order:operate')")
    public R<OrderDetailView> startOperation(@PathVariable Long id) {
        return R.ok(service.startOperation(id));
    }

    /**
     * POST /api/v1/orders/{id}/deliver — 交付 (IN_OPERATION → DELIVERED)。
     *
     * @return {@code R<OrderDetailView>}
     */
    @PostMapping("/{id}/deliver")
    @PreAuthorize("hasAuthority('order:operate')")
    public R<OrderDetailView> deliver(@PathVariable Long id) {
        return R.ok(service.deliver(id));
    }

    /**
     * POST /api/v1/orders/{id}/settle — 结算 (DELIVERED → SETTLED)。
     *
     * @return {@code R<OrderDetailView>}
     */
    @PostMapping("/{id}/settle")
    @PreAuthorize("hasAuthority('order:settle')")
    public R<OrderDetailView> settle(@PathVariable Long id) {
        return R.ok(service.settle(id));
    }

    /**
     * POST /api/v1/orders/{id}/close — 关账 (SETTLED → CLOSED)。
     *
     * <p>body 可缺省 ({@code required = false}); 缺省时 reason 传 null 给 service,
     * 由 {@code requireReason} 抛 {@code REASON_REQUIRED} (HTTP 400)。
     *
     * @return {@code R<OrderDetailView>}
     */
    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('order:close')")
    public R<OrderDetailView> close(@PathVariable Long id,
                                     @RequestBody(required = false) Map<String, String> body) {
        return R.ok(service.close(id, body == null ? null : body.get("reason")));
    }
}
