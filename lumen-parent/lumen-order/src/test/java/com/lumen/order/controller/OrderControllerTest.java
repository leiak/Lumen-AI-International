package com.lumen.order.controller;

import com.lumen.common.api.PageResult;
import com.lumen.order.OrderConstants;
import com.lumen.order.dto.OrderDetailView;
import com.lumen.order.dto.OrderView;
import com.lumen.order.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderController wire contract 单测 (Iteration 4.6, Task 5).
 *
 * <p>照 {@code CsExceptionControllerTest} 的做法: 直接调 controller 方法
 * (service 用 Mockito 注入), 不用 MockMvc —— 锁的是「参数名 / 返回信封 / 分页口径」
 * 这些前端真正依赖的形状。
 *
 * <p>另外两条反射用例锁的是**注解层**的形状 (纯单元测试走不到 Spring 的参数解析阶段,
 * 反射是本模块唯一能在不启容器的前提下把这些锁住的办法):
 * <ul>
 *   <li>带 reason 的 4 个端点的 {@code @RequestBody} 必须是 {@code required = false}</li>
 *   <li>13 个端点的路径与 {@code @PreAuthorize} 的权限码</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderController 单元测试 (wire contract)")
class OrderControllerTest {

    @Mock private OrderService service;

    @InjectMocks
    private OrderController controller;

    @Test
    @DisplayName("page: 分页参数名 current/size, 返回 R<PageResult<OrderView>>")
    void page_usesCurrentAndSize() {
        OrderView v = new OrderView(1L, "ORD-20261110-000001", 42L, "OCEAN_FCL", "EXPORT",
                "FOB", "D2D", "上海", "洛杉矶", null, null, null,
                OrderConstants.STATUS_DRAFT, "CNY", null, null, null);
        when(service.page(anyLong(), anyLong(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(PageResult.of(List.of(v), 1, 1, 20));

        var res = controller.page(1L, 20L, null, null, null, null);

        assertThat(res.getCode()).isZero();
        assertThat(res.getData().getRecords()).hasSize(1);
        assertThat(res.getData().getPageNum()).isEqualTo(1);
        assertThat(res.getData().getPageSize()).isEqualTo(20);
        verify(service).page(eq(1L), eq(20L), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("get: 返回 R<OrderDetailView>")
    void get_returnsDetail() {
        OrderDetailView d = new OrderDetailView(
                1L,                             // id
                "ORD-20261110-000001",          // orderNo
                42L,                            // customerId
                "OCEAN_FCL",                    // businessType
                "EXPORT",                       // tradeDirection
                "FOB",                          // incoterms
                "D2D",                          // serviceType
                null,                           // originAddress
                null,                           // destAddress
                null,                           // totalQty
                null,                           // totalWeightKg
                null,                           // totalVolumeCbm
                OrderConstants.STATUS_DRAFT,    // orderStatus
                "CNY",                          // settlementCurrency
                null,                           // etd
                null,                           // eta
                null,                           // createdAt
                null,                           // cargoReadyDate
                null,                           // containerQty
                null,                           // containerTypeCode
                null,                           // marksAndNos
                null,                           // cargoDescription
                null,                           // isDangerous
                null,                           // isOversized
                null,                           // temperatureRange
                null,                           // remark
                null,                           // cancelReason
                null,                           // closeReason
                null,                           // voidReason
                null,                           // submittedAt
                null,                           // confirmedAt
                null,                           // deliveredAt
                null,                           // settledAt
                null,                           // closedAt
                null,                           // createdBy
                null,                           // updatedBy
                List.of(),                      // cargo
                List.of(),                      // serviceItems
                List.of(OrderConstants.STATUS_PENDING_CONFIRM)); // allowedNextStates
        when(service.get(1L)).thenReturn(d);

        var res = controller.get(1L);

        assertThat(res.getCode()).isZero();
        assertThat(res.getData().orderNo()).isEqualTo("ORD-20261110-000001");
        assertThat(res.getData().allowedNextStates())
                .containsExactly(OrderConstants.STATUS_PENDING_CONFIRM);
    }

    @Test
    @DisplayName("cancel: body 缺省 (null) → reason 传 null 给 service, 由 service 抛 REASON_REQUIRED")
    void cancel_nullBody_passesNullReasonToService() {
        // @RequestBody(required = false) 是这条链路的生死线 ——
        // body 缺省时必须能进到方法体 (而不是在参数解析阶段炸成 500),
        // 缺省的原因由 service 的 requireReason 挡成 400。
        when(service.cancel(1L, null)).thenReturn(detail());

        var res = controller.cancel(1L, null);

        assertThat(res.getCode()).isZero();
        verify(service).cancel(1L, null);
    }

    /**
     * 带 reason 的 4 个端点的 {@code @RequestBody} 必须是 {@code required = false}。
     *
     * <p>{@code @RequestBody} 默认 {@code required = true} —— 客户端不带 body 时 Spring
     * 在参数解析阶段就抛 {@code HttpMessageNotReadableException}, 方法体进不去,
     * 那层 {@code body == null} 兜底形同虚设; 而 {@code GlobalExceptionHandler} 没有
     * 这个异常的分支 → 落 catch-all → HTTP 500 (而不是 service 抛的 400)。
     */
    @Test
    @DisplayName("return/cancel/void/close: @RequestBody 必须是 required=false (body 可缺省)")
    void reasonEndpoints_bodyIsOptional() throws Exception {
        for (String name : List.of("returnToDraft", "cancel", "voidOrder", "close")) {
            Method m = OrderController.class.getMethod(name, Long.class, Map.class);
            RequestBody rb = m.getParameters()[1].getAnnotation(RequestBody.class);
            assertThat(rb).as("%s 的 body 参数必须带 @RequestBody", name).isNotNull();
            assertThat(rb.required()).as("%s 的 @RequestBody 必须 required=false", name).isFalse();
        }
    }

    /**
     * 13 个端点的路径与 {@code @PreAuthorize} 权限码 —— 逐条对照
     * {@code lumen-order/src/main/resources/db/migration/V42__order_schema.sql} 里
     * seed 的 10 个 {@code order:*} 权限码 (order:view / order:create / order:edit /
     * order:submit / order:confirm / order:cancel / order:void / order:operate /
     * order:settle / order:close)。抄错一个字母 → 那个端点永远 403, 所以在这里锁死。
     */
    @Test
    @DisplayName("13 个端点: 路径与 @PreAuthorize 权限码逐条对应 V42 seed")
    void endpoints_bindExpectedPathsAndAuthorities() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("GET /api/v1/orders", "order:view");
        expected.put("GET /api/v1/orders/{id}", "order:view");
        expected.put("POST /api/v1/orders", "order:create");
        expected.put("PUT /api/v1/orders/{id}", "order:edit");
        expected.put("POST /api/v1/orders/{id}/submit", "order:submit");
        expected.put("POST /api/v1/orders/{id}/confirm", "order:confirm");
        expected.put("POST /api/v1/orders/{id}/return", "order:submit");
        expected.put("POST /api/v1/orders/{id}/cancel", "order:cancel");
        expected.put("POST /api/v1/orders/{id}/void", "order:void");
        expected.put("POST /api/v1/orders/{id}/start-operation", "order:operate");
        expected.put("POST /api/v1/orders/{id}/deliver", "order:operate");
        expected.put("POST /api/v1/orders/{id}/settle", "order:settle");
        expected.put("POST /api/v1/orders/{id}/close", "order:close");

        Map<String, String> actual = new LinkedHashMap<>();
        for (Method m : OrderController.class.getDeclaredMethods()) {
            String mapping = mappingOf(m);
            PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
            assertThat(mapping).as("%s 必须有 HTTP 方法映射", m.getName()).isNotNull();
            assertThat(pa).as("%s 必须有 @PreAuthorize", m.getName()).isNotNull();
            assertThat(pa.value()).as("%s 的 @PreAuthorize 必须是 hasAuthority 形式", m.getName())
                    .startsWith("hasAuthority('").endsWith("')");
            actual.put(mapping, pa.value().substring("hasAuthority('".length(), pa.value().length() - 2));
        }

        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }

    /** 把方法上的 Spring MVC 映射注解还原成 "METHOD /path" 的字面量。 */
    private String mappingOf(Method m) {
        String prefix = OrderController.class.getAnnotation(RequestMapping.class).value()[0];
        GetMapping get = m.getAnnotation(GetMapping.class);
        if (get != null) {
            return "GET " + prefix + pathOf(get.value());
        }
        PostMapping post = m.getAnnotation(PostMapping.class);
        if (post != null) {
            return "POST " + prefix + pathOf(post.value());
        }
        PutMapping put = m.getAnnotation(PutMapping.class);
        if (put != null) {
            return "PUT " + prefix + pathOf(put.value());
        }
        return null;
    }

    /** 注解上没写路径时 (如 {@code @PostMapping} 裸写), value() 是空数组。 */
    private String pathOf(String[] values) {
        return values.length == 0 ? "" : values[0];
    }

    private OrderDetailView detail() {
        return new OrderDetailView(
                1L,                             // id
                "ORD-20261110-000001",          // orderNo
                42L,                            // customerId
                "OCEAN_FCL",                    // businessType
                "EXPORT",                       // tradeDirection
                "FOB",                          // incoterms
                "D2D",                          // serviceType
                null,                           // originAddress
                null,                           // destAddress
                null,                           // totalQty
                null,                           // totalWeightKg
                null,                           // totalVolumeCbm
                OrderConstants.STATUS_DRAFT,    // orderStatus
                "CNY",                          // settlementCurrency
                null,                           // etd
                null,                           // eta
                null,                           // createdAt
                null,                           // cargoReadyDate
                null,                           // containerQty
                null,                           // containerTypeCode
                null,                           // marksAndNos
                null,                           // cargoDescription
                null,                           // isDangerous
                null,                           // isOversized
                null,                           // temperatureRange
                null,                           // remark
                null,                           // cancelReason
                null,                           // closeReason
                null,                           // voidReason
                null,                           // submittedAt
                null,                           // confirmedAt
                null,                           // deliveredAt
                null,                           // settledAt
                null,                           // closedAt
                null,                           // createdBy
                null,                           // updatedBy
                List.of(),                      // cargo
                List.of(),                      // serviceItems
                List.of());                     // allowedNextStates
    }
}
