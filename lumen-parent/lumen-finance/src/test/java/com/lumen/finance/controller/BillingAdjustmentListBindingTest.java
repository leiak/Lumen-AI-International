package com.lumen.finance.controller;

import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.service.AdjustmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 4.5C-2.1 — {@code BillingAdjustmentController.list} 的 <b>HTTP 层参数绑定</b>守卫。
 *
 * <p><b>本测试守的是什么</b>: 规范 [N13-7] 要求 list 端点接受
 * {@code ?disputed=true|false} 并把值透传给 service。本类只管<b>这一层</b>:
 * 请求里的 query 参数能不能被 Spring 绑成 {@code Boolean}, 以及绑定失败时是不是 400。
 *
 * <p><b>为什么 service 层的测试不够</b>: 现有的
 * {@code AdminAdjustmentDisputeIT#list_filtersByDisputed} 是<b>直接调 service</b> 的
 * ({@code adjustmentService.list(tenantId, null, Boolean.TRUE, 1, 50)}) —— 它压根没经过
 * controller, 所以 controller 那一行签名是死是活它一无所知。全仓 test 目录 grep
 * {@code BillingAdjustmentController} 零命中 → controller 层零覆盖。
 *
 * <p><b>真实风险</b>: 把 {@code Boolean disputed} 拼成 {@code disputedFlag}、或者把类型
 * 改成 {@code String}, 那么 {@code ?disputed=true} 要么 400 要么<b>静默不过滤</b> ——
 * 而两种情况下全仓测试<b>仍然全绿</b>。财务以为在筛商家异议, 其实拿到的是全量。
 *
 * <p><b>与 {@code AdjustmentDisputeSnapshotTest} 的分工</b>: 那个类守 service 层
 * {@code disputed} 的三态语义 (null=不过滤 / TRUE=只回有异议 / FALSE=只回无异议,
 * 对应 [N13-5] / [N13-6]); 本类守 HTTP 层的绑定 (对应 [N13-7])。两者合起来才把
 * [N13-5] / [N13-6] / [N13-7] 覆盖完整 —— 缺任何一边都是缺口。
 *
 * <p>⚠️ <b>本测试不守鉴权</b>: {@code standaloneSetup} 下没有 SecurityFilterChain,
 * 方法级 {@code @PreAuthorize} 不生效 (没有 Security 的 AOP 代理), 所以这里
 * <b>不会</b>出现 403, 也<b>不</b>能拿来断言权限。权限由别处负责。
 *
 * <p>⚠️ <b>参数与断言都要精确</b>: 本测试的全部意义就是「{@code disputed} 到底被绑成了
 * 什么」, 所以用 {@code eq(Boolean.TRUE)} / {@code isNull()} 精确断言, 不用
 * {@code any()} 糊过去 —— {@code any()} 会把「传错了值」也一起放过。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingAdjustmentController.list — disputed 的 HTTP 层参数绑定")
class BillingAdjustmentListBindingTest {

    private static final Long TENANT = 42L;
    private static final String URL = "/api/v1/finance/adjustments";

    @Mock
    private AdjustmentService adjustmentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // controller 里是 TenantContext.require() —— 没有租户上下文会直接抛
        // IllegalStateException, 根本走不到参数绑定那一步。
        TenantContext.set(TENANT);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BillingAdjustmentController(adjustmentService))
                .build();
    }

    @AfterEach
    void tearDown() {
        // ThreadLocal 不清会漏到同线程的下一个用例 (Surefire 复用线程)。
        TenantContext.clear();
    }

    @Test
    @DisplayName("?disputed=true → service 收到 Boolean.TRUE (不是字符串「true」)")
    void list_disputedTrue_bindsToBooleanTrue() throws Exception {
        when(adjustmentService.list(anyLong(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get(URL).queryParam("disputed", "true"))
                .andExpect(status().isOk());

        // 精确断言: disputed 必须是 Boolean.TRUE。写成 eq("true") 的话, 实现退化成
        // String 也能过 —— 而那正是本测试要抓的退化。
        verify(adjustmentService).list(eq(TENANT), isNull(), eq(Boolean.TRUE), eq(1), eq(20));
    }

    @Test
    @DisplayName("?disputed=false → service 收到 Boolean.FALSE (不能当成 null 即不过滤)")
    void list_disputedFalse_bindsToBooleanFalse() throws Exception {
        when(adjustmentService.list(anyLong(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get(URL).queryParam("disputed", "false"))
                .andExpect(status().isOk());

        // 关键: 必须是 FALSE 而不是 null —— null 的语义是「不过滤」, 与 FALSE 相反。
        verify(adjustmentService).list(eq(TENANT), isNull(), eq(Boolean.FALSE), eq(1), eq(20));
    }

    @Test
    @DisplayName("不传 disputed → service 收到 null (三态里的「不过滤」)")
    void list_absentDisputed_bindsToNull() throws Exception {
        when(adjustmentService.list(anyLong(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get(URL))
                .andExpect(status().isOk());

        verify(adjustmentService).list(eq(TENANT), isNull(), isNull(), eq(1), eq(20));
    }

    @Test
    @DisplayName("?disputed=garbage → HTTP 400, 且 service 根本没被调用")
    void list_disputedGarbage_isBadRequestAndServiceNotCalled() throws Exception {
        // 注意: 这里<b>不</b> stub service —— 请求不该走到 service。
        mockMvc.perform(get(URL).queryParam("disputed", "garbage"))
                .andExpect(status().isBadRequest());

        // 绑定失败就该在进 controller 之前被拦下, 不能带着半截参数去查库。
        verify(adjustmentService, never()).list(anyLong(), any(), any(), anyInt(), anyInt());
    }
}
