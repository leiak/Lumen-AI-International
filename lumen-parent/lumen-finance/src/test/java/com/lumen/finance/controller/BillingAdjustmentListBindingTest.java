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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 4.5C-2.1 — {@code BillingAdjustmentController.list} 的 <b>HTTP 层参数绑定</b>守卫。
 *
 * <p><b>本测试守的是什么</b>: 规范 [N13-7] 要求 list 端点接受
 * {@code ?disputed=true|false} 并把值透传给 service。本类只管<b>这一层</b>:
 * 请求里的 query 参数能不能被 Spring 绑成 {@code Boolean}, 以及绑定失败时请求是不是
 * <b>在进 controller 方法体之前</b>就被拦下 (service 从未被调用)。
 *
 * <p><b>为什么 service 层的测试不够</b>: 现有的
 * {@code AdminAdjustmentDisputeIT#list_filtersByDisputed} 是<b>直接调 service</b> 的
 * ({@code adjustmentService.list(tenantId, null, Boolean.TRUE, 1, 50)}) —— 它压根没经过
 * controller, 所以 controller 那一行签名是死是活它一无所知。
 * <b>修复前</b>, 全仓 test 目录 grep {@code BillingAdjustmentController} <b>零命中</b> →
 * controller 层零覆盖; <b>本类就是补这个缺口的</b> (加进来之后, 本文件自己就成了
 * 那个 grep 的命中点)。
 *
 * <p><b>真实风险</b>: 把 {@code Boolean disputed} 拼成 {@code disputedFlag}、或者把类型
 * 改成 {@code String} —— <b>两种退化都不会让请求变成 400</b>: 请求照常返回 200,
 * 而过滤<b>静默失效</b>:
 * <ul>
 *   <li>参数名拼错 → {@code ?disputed=...} 被当成未知参数<b>忽略</b> → 绑成 {@code null}
 *       → 静默「不过滤」, 财务拿到的是全量;</li>
 *   <li>类型降级成 {@code String} → 值绑上了却兜底转换, 缺省 / 非法值被静默当成
 *       {@code false} → 静默按「无异议」筛。</li>
 * </ul>
 * 两种退化都不会报错, 财务以为在筛商家异议, 其实筛出来的根本不是他要的那批,
 * 而全仓测试<b>仍然全绿</b>。
 *
 * <p><b>为什么本测试不锁 HTTP 状态码</b> (非法值那一条): 裸状态码<b>取决于全局异常
 * advice 在哪个环境生效</b> ——
 * <ul>
 *   <li><b>切片</b> ({@code standaloneSetup} 不加载任何 {@code @RestControllerAdvice}) →
 *       落到 Spring 默认的 {@code DefaultHandlerExceptionResolver} → <b>400</b>;</li>
 *   <li><b>生产</b> → {@code com.lumen.common.error.GlobalExceptionHandler} 是<b>无 scope</b>
 *       的 {@code @RestControllerAdvice} → 它的 catch-all
 *       {@code @ExceptionHandler(Exception.class) handleAny(...)} 接住了
 *       {@code MethodArgumentTypeMismatchException} → <b>500</b>
 *       (它的 {@code handleValidation} 只接 {@code BindException} /
 *       {@code MethodArgumentNotValidException}, 接不住类型不匹配)。</li>
 * </ul>
 * 锁死 400 等于把「切片环境的产物」当成系统契约, 锁死 500 同理 —— 两者都不是 200,
 * 但<b>都不是</b>跨环境恒真的东西。真正该锁的、与环境无关的那条是:
 * <b>请求在进 controller 方法体之前就被拦下, service 从未被调用</b>。
 *
 * <p>⚠️ <b>「生产当前返回 500」是本轮审查新发现的既有缺陷, 不在本轮修复范围</b>:
 * {@code GlobalExceptionHandler} 缺一个
 * {@code @ExceptionHandler(MethodArgumentTypeMismatchException.class)} → 400。
 * 本轮 (4.5C-2.1 最终整体审查的修复轮) <b>只碰测试文件, 生产代码一行不改</b> ——
 * 那个修法还会改动所有模块的错误码语义, 需要单开一轮「质量与正确性」处理。
 * 该缺陷已记入 README §15.17 的「已知缺口 15」与下一轮候选。
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

        // 精确断言: disputed 必须是 Boolean.TRUE (三态里的「只回有异议」)。
        // 这里不能写 any(): any() 会把「传错了值 / 没绑上」一起放过;
        // 也不能退化成字符串比较 —— 真要退化的话, 实现要么用 any() 糊过去,
        // 要么把形参改成 String 再 valueOf 转换 —— 那正是本测试要抓的退化。
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
    @DisplayName("?disputed=garbage → 请求在进 controller 前被拦下, service 根本不被调用")
    void list_disputedGarbage_requestRejectedBeforeControllerAndServiceNotCalled() throws Exception {
        // 注意: 这里<b>不</b> stub service —— 请求不该走到 service。
        //
        // <b>刻意不断言具体状态码</b>: 同一条请求在切片里是 400、在生产里是 500
        // (原因见类 javadoc) —— 锁死任一个都是把「某个环境的产物」当成系统契约。
        // 跨环境恒真的只有「service 从未被调用」。
        mockMvc.perform(get(URL).queryParam("disputed", "garbage"));

        // 绑定失败就该在进 controller 之前被拦下, 不能带着半截参数去查库。
        // 这一条不是恒真: 把形参拼错 (disputedFlag) 或降级成 String 时, 请求会绑上,
        // service <b>会</b>被调用 → 这条会红 (变异 W2 / W3 实测过)。
        verify(adjustmentService, never()).list(anyLong(), any(), any(), anyInt(), anyInt());
        // 更强的一条: service 上不该有任何交互 (不只是 list 没被调)。
        verifyNoInteractions(adjustmentService);
    }

    @Test
    @DisplayName("?status=PENDING_L1 → service 收到这个字面量 (status 参数名/值没被吞掉)")
    void list_statusPropagated_toService() throws Exception {
        when(adjustmentService.list(anyLong(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get(URL).queryParam("status", "PENDING_L1"))
                .andExpect(status().isOk());

        // 守两件事: status 的<b>参数名</b>没被改 (改成 statusFlag 的话, ?status=... 会被
        // 当成未知参数忽略 → 传进来的是 null), 以及它的<b>值</b>被原样透传给 service。
        // status 是 String, 不做校验 —— 这里只守「绑上了、且原样传下去了」。
        verify(adjustmentService).list(eq(TENANT), eq("PENDING_L1"), isNull(), eq(1), eq(20));
    }

    @Test
    @DisplayName("?page=3&size=50 → service 收到 3 / 50 (分页参数没被写死成默认值)")
    void list_pageAndSize_propagatedToService() throws Exception {
        when(adjustmentService.list(anyLong(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get(URL).queryParam("page", "3").queryParam("size", "50"))
                .andExpect(status().isOk());

        // 守的是「page / size 是从请求里拿的, 不是写死的 1 / 20」。
        // 注意 AdjustmentService.list 的第 4 / 5 参是<b>原始 int</b> → 用 eq(3) / eq(50)。
        verify(adjustmentService).list(eq(TENANT), isNull(), isNull(), eq(3), eq(50));
    }
}
