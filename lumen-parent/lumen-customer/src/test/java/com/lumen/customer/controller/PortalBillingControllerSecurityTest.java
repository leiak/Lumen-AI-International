package com.lumen.customer.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import com.lumen.finance.dto.portal.MerchantDtos.DisputeRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.5C-2 — 10 个账单端点的 {@code @PreAuthorize} 契约。
 *
 * <p>本仓<b>没有</b> spring-security-test, controller 测试也没有先例用 MockMvc,
 * 所以这里用反射锁注解字面量。这不是"退而求其次": 权限注解写错 (抄错码、漏写)
 * 在编译期和运行期都不报错, 只在真有人拿错 token 打进来时才炸, 而且炸的是 403
 * 而不是测试。用反射在 CI 里就把它炸出来。
 */
@DisplayName("PortalBilling controllers — 权限注解契约")
class PortalBillingControllerSecurityTest {

    private static String authz(Class<?> type, String method, Class<?>... params) throws Exception {
        // 注意 findMergedAnnotation 返回的是**注解实例**而不是 Method —— 泛型 A 由
        // PreAuthorize.class 推出, 所以变量类型得是 PreAuthorize, 写成 Method 会编译不过。
        PreAuthorize m = AnnotatedElementUtils.findMergedAnnotation(
                type.getDeclaredMethod(method, params), PreAuthorize.class);
        assertThat(m).as("%s#%s 必须有 @PreAuthorize", type.getSimpleName(), method).isNotNull();
        return m.value();
    }

    private static String routeOf(Class<?> type) {
        RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        assertThat(rm).as("%s 必须有 @RequestMapping", type.getSimpleName()).isNotNull();
        assertThat(rm.value()).hasSize(1);
        return rm.value()[0];
    }

    @Test
    @DisplayName("4 个 controller 的路由前缀都是 /api/customer/*")
    void routePrefixes() {
        assertThat(routeOf(PortalReconController.class)).isEqualTo("/api/customer/recon");
        assertThat(routeOf(PortalInvoiceController.class)).isEqualTo("/api/customer/invoice");
        assertThat(routeOf(PortalPaymentController.class)).isEqualTo("/api/customer/payment");
        assertThat(routeOf(PortalAdjustmentController.class)).isEqualTo("/api/customer/adjustment");
    }

    @Test
    @DisplayName("GET / 端点")
    void listEndpoints() throws Exception {
        // 反射签名 = 控制器方法的**参数表**, 与实现必须逐个对上。
        // 注意 tenantId/counterpartyId 走的是 PortalIdentity 的静态方法,
        // 在方法体内取值, **不是**参数 —— 所以这里没有那两个 Long。
        assertThat(authz(PortalReconController.class, "list", String.class))
                .isEqualTo("hasAuthority('customer:recon:view')");
        assertThat(authz(PortalInvoiceController.class, "list", String.class, String.class))
                .isEqualTo("hasAuthority('customer:invoice:view')");
        assertThat(authz(PortalPaymentController.class, "list", String.class))
                .isEqualTo("hasAuthority('customer:payment:view')");
        assertThat(authz(PortalAdjustmentController.class, "list", Long.class, Long.class))
                .isEqualTo("hasAuthority('customer:adjustment:view')");
    }

    @Test
    @DisplayName("GET /{id} 端点")
    void detailEndpoints() throws Exception {
        assertThat(authz(PortalReconController.class, "detail", Long.class))
                .isEqualTo("hasAuthority('customer:recon:view')");
        assertThat(authz(PortalInvoiceController.class, "detail", Long.class))
                .isEqualTo("hasAuthority('customer:invoice:view')");
        assertThat(authz(PortalAdjustmentController.class, "detail", Long.class))
                .isEqualTo("hasAuthority('customer:adjustment:view')");
    }

    @Test
    @DisplayName("GET /export 端点")
    void exportEndpoints() throws Exception {
        assertThat(authz(PortalReconController.class, "export", String.class))
                .isEqualTo("hasAuthority('customer:recon:view')");
        assertThat(authz(PortalInvoiceController.class, "export", String.class, String.class))
                .isEqualTo("hasAuthority('customer:invoice:view')");
    }

    @Test
    @DisplayName("POST /dispute 走 customer:adjustment:create, 不是 recon:view")
    void disputeUsesAdjustmentCreate() throws Exception {
        // 只读权限不能推进审批 —— 这两个动作的权限必须分开
        assertThat(authz(PortalReconController.class, "dispute", DisputeRequest.class))
                .isEqualTo("hasAuthority('customer:adjustment:create')");
    }

    @Test
    @DisplayName("每个 controller 里除路径/类级注解外没有漏网的 handler")
    void everyHandlerIsCovered() {
        // 4 个 controller × 端点数 = 10。加新端点忘了写权限, 这里会红。
        assertThat(countHandlers(PortalReconController.class)).isEqualTo(4);
        assertThat(countHandlers(PortalInvoiceController.class)).isEqualTo(3);
        assertThat(countHandlers(PortalPaymentController.class)).isEqualTo(1);
        assertThat(countHandlers(PortalAdjustmentController.class)).isEqualTo(2);
    }

    /**
     * 统计 handler 数量, 并<b>顺带断言每个 handler 都带 {@code @PreAuthorize}</b>。
     *
     * <p>只断言个数是不够的: 新增第 5 个端点后测试会红, 但开发者的自然动作是
     * <b>把数字改成 5</b> —— 此时新端点的权限码从未被任何断言覆盖, 测试转绿。
     * 而这恰恰是这个测试想防的那类 bug: {@code SecurityConfig} 的 URL 层只有
     * {@code anyRequest().authenticated()}, 方法级注解是唯一闸门, 漏了 = 任何持
     * portal token 的用户都能调。
     *
     * <p>把断言放进循环后, "新增端点忘加权限"无论开发者的第一反应是改数字还是加断言,
     * 都必红。
     */
    private static int countHandlers(Class<?> type) {
        int n = 0;
        for (Method m : type.getDeclaredMethods()) {
            if (AnnotatedElementUtils.hasAnnotation(m, org.springframework.web.bind.annotation.RequestMapping.class)) {
                assertThat(AnnotatedElementUtils.findMergedAnnotation(m, PreAuthorize.class))
                        .as("%s#%s 必须有 @PreAuthorize", type.getSimpleName(), m.getName())
                        .isNotNull();
                n++;
            }
        }
        return n;
    }
}
