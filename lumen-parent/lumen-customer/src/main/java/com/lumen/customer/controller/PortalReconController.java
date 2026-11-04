package com.lumen.customer.controller;

import com.lumen.common.api.R;
import com.lumen.customer.error.PortalErrorCode;
import com.lumen.customer.exception.CustomerException;
import com.lumen.customer.web.PortalDownloads;
import com.lumen.customer.web.PortalIdentity;
import com.lumen.finance.dto.portal.MerchantDtos.DisputeRequest;
import com.lumen.finance.dto.portal.MerchantDtos.DisputeResult;
import com.lumen.finance.dto.portal.MerchantDtos.ReconView;
import com.lumen.finance.dto.portal.MerchantPage;
import com.lumen.finance.service.MerchantBillingViewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 客户门户对账 controller (Iteration 4.5C-2).
 *
 * <p>4 个端点 (路由前缀 {@code /api/customer/recon}):
 * <ol>
 *   <li>GET  {@code /}          — {@code customer:recon:view}       — 列表</li>
 *   <li>GET  {@code /{id}}      — {@code customer:recon:view}       — 详情</li>
 *   <li>GET  {@code /export}    — {@code customer:recon:view}       — xlsx 导出</li>
 *   <li>POST {@code /dispute}   — {@code customer:adjustment:create} — 提异议</li>
 * </ol>
 *
 * <p><b>{@code /dispute} 不带路径参数, {@code reconId} 走 body。</b> 一次提异议只针对
 * 一张对账单, 走 body 与其余写操作的形状一致 (4.5B.2 的
 * {@code POST /api/customer/order/{doId}/cancel} 带路径参数是因为"取消哪一单"是路径
 * 语义的一部分, 而异议更像一次针对账单的声明)。
 *
 * <p><b>/export 与 /{id} 的优先级</b>: Spring MVC 的模式比较器把字面量段排在模板段
 * 之上, 所以 {@code /export} 不会被 {@code /{id}} 抢走 —— 这与声明顺序无关, 调换
 * 声明位置也不影响。只有把 {@code /export} 挪到 {@code /{id}} 之下、或改成通配路由
 * ({@code /{*path}}) 这个保证才会消失。{@code {id}} 的 Java 绑定类型从 {@code Long}
 * 放宽成 {@code String} <b>不影响</b>它 —— 特异性比较发生在<b>路径模式</b>层面
 * (字面量段 vs 捕获变量), 与 Java 侧的绑定类型无关; 放宽后非数字 id 只是从
 * "400 类型不匹配"变成"查不到 → 404", 不会把 {@code /export} 抢走。
 *
 * <p>tenantId / counterpartyId / userId 一律走 {@link PortalIdentity} 从 JWT 取,
 * <b>不接受任何来自请求体的 counterpartyId</b>。body 里的 {@code reconId} 是"对哪张
 * 单提异议", 与"我是哪个商家"是两件事, 后者永远来自 JWT。
 *
 * <p><b>{@code period} 的格式校验刻意放在 service 层, 不在这里加
 * {@code @Pattern}</b>。本仓的 {@code GlobalExceptionHandler} 处理的是
 * {@code MethodArgumentNotValidException} (即 {@code @Valid @RequestBody} 的 DTO 校验),
 * <b>没有 {@code ConstraintViolationException} 的分支</b> —— 而类级 {@code @Validated}
 * + 方法参数 {@code @Pattern} 抛的正是后者, 会落 catch-all 变成 HTTP 500。
 * 商家传 {@code ?period=2026-13} 该回 400, 不是"服务器内部错误"。
 * service 层抛 {@code FinanceErrorCode.BAD_REQUEST} (400_022) 由 Task 4 的翻译表
 * 翻成门户 400, 这条链路是通的。
 */
@RestController
@RequestMapping("/api/customer/recon")
@RequiredArgsConstructor
public class PortalReconController {

    private final MerchantBillingViewService billingView;

    /**
     * {@code dispute_amount} 是 {@code DECIMAL(18,4)} — 14 位整数 + 4 位小数。
     * 超了不是"显示精度不够", 是 MySQL 直接报 Data truncation 让整个请求 500,
     * 所以在入口挡掉。
     */
    private static final BigDecimal MAX_CLAIM = new BigDecimal("99999999999999.9999");

    /**
     * {@code dispute_reason} 是 {@code VARCHAR(500)} (V39)。
     * 超了不是"显示截断", 是 MySQL strict mode 报 Data too long 让整个请求 500 ——
     * 与 {@link #MAX_CLAIM} 同一个道理, 所以在入口挡掉。
     */
    private static final int MAX_REASON_LENGTH = 500;

    // GET / — 列表
    @GetMapping
    @PreAuthorize("hasAuthority('customer:recon:view')")
    public R<MerchantPage<ReconView>> list(@RequestParam(required = false) String period) {
        return R.ok(billingView.listReconciliations(
                PortalIdentity.requireTenantId(),
                PortalIdentity.requireCounterpartyId(),
                period, 1, 20));
    }

    // GET /{id} — 详情
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('customer:recon:view')")
    public R<ReconView> detail(@PathVariable Long id) {
        return R.ok(billingView.getReconciliation(
                PortalIdentity.requireTenantId(), PortalIdentity.requireCounterpartyId(), id));
    }

    // GET /export — 导出
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('customer:recon:view')")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String period) {
        byte[] body = billingView.exportReconciliations(
                PortalIdentity.requireTenantId(), PortalIdentity.requireCounterpartyId(), period);
        return PortalDownloads.xlsx("对账单.xlsx", body);
    }

    /**
     * {@code POST /dispute} — 对某张对账单的差异提异议。
     *
     * <p><b>形状校验在这里, 业务校验在 finance 侧</b>:
     * <ul>
     *   <li>形状 (本方法): body 非空 / reconId 非空 / reason 非空且不超长 /
     *       claimedAmount 落在 DECIMAL(18,4) 可表示范围内</li>
     *   <li>业务 (finance 侧): 已异议过 / 期间已闭期 / 无关联调账单 / 对账单不属于该商家</li>
     * </ul>
     *
     * <p><b>形状校验刻意排在取身份之前</b>。两个原因:
     * <ol>
     *   <li>语义 —— "你传的参数不合法"优先于"你是谁", 前者不需要查任何数据就能定</li>
     *   <li>可测 —— 校验本身是纯函数 (不碰任何 bean), 排前面就能脱开 Spring 上下文
     *       直接单测 (见 {@code PortalReconControllerTest})</li>
     * </ol>
     *
     * <p><b>{@code @RequestBody(required = false)} 不是可选项</b>: 默认的
     * {@code required = true} 会让空 body 在<b>参数解析阶段</b>就抛
     * {@code HttpMessageNotReadableException}, 商家收到的是 HTTP 500 "服务器内部错误";
     * 而"你没说是哪张对账单"是入参错误, 该回 400_005。
     *
     * @throws CustomerException 本方法抛的形状错误:
     *         400_005 body/reconId 缺失或理由过长 / 400_003 理由为空 /
     *         400_004 主张金额超出 DECIMAL(18,4) 可表示范围
     * @throws com.lumen.finance.exception.FinanceException 经
     *         {@code CustomerExceptionHandler} 的翻译表变成门户码:
     *         404_002 对账单不存在或不属于该商家 /
     *         409_002 该期对账单当前不可提异议 (已异议过 / 已闭期 / 无关联调账单)
     */
    @PostMapping("/dispute")
    @PreAuthorize("hasAuthority('customer:adjustment:create')")
    public R<DisputeResult> dispute(@RequestBody(required = false) DisputeRequest body) {
        // ---------- 形状校验 (纯函数, 在取身份之前) ----------

        // 空 body / reconId 为空是**入参错误**, 不是"资源不存在" ——
        // 我们根本没去查任何一张对账单, 回 404_002 等于告诉商家
        // "你说的那张对账单不存在", 而真相是"你压根没说是哪一张"。
        if (body == null || body.reconId() == null) {
            throw new CustomerException(PortalErrorCode.PORTAL_PARAM_INVALID.getCode(),
                    PortalErrorCode.PORTAL_PARAM_INVALID.getMessage());
        }
        if (body.reason() == null || body.reason().isBlank()) {
            throw new CustomerException(PortalErrorCode.DISPUTE_REASON_REQUIRED.getCode(),
                    PortalErrorCode.DISPUTE_REASON_REQUIRED.getMessage());
        }
        // 长度校验放在 trim 之后 —— 存进库的是 trim 过的值, 该按它判长度。
        // String.length() 数的是 UTF-16 code unit, MySQL VARCHAR 数的是字符 ——
        // emoji 会被 Java 记 2、MySQL 记 1, 所以 Java 的判定更严, 不会放过超长值。
        String reason = body.reason().trim();
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new CustomerException(PortalErrorCode.PORTAL_PARAM_INVALID.getCode(),
                    "异议理由过长 (最多 " + MAX_REASON_LENGTH + " 字)");
        }
        BigDecimal claimed = body.claimedAmount();
        if (claimed != null && (claimed.abs().compareTo(MAX_CLAIM) > 0
                || Math.max(claimed.scale(), 0) > 4)) {
            throw new CustomerException(PortalErrorCode.DISPUTE_AMOUNT_INVALID.getCode(),
                    PortalErrorCode.DISPUTE_AMOUNT_INVALID.getMessage());
        }

        // ---------- 身份 (唯一的 counterpartyId 来源) ----------

        Long tenantId = PortalIdentity.requireTenantId();
        Long counterpartyId = PortalIdentity.requireCounterpartyId();
        Long userId = PortalIdentity.requireUserId();

        return R.ok(billingView.raiseDispute(tenantId, counterpartyId, userId,
                new DisputeRequest(body.reconId(), claimed, reason)));
    }
}
