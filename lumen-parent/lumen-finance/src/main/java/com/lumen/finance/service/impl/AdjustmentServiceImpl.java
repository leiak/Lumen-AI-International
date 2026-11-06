package com.lumen.finance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.extension.state.StateMachineEngine;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.engine.CurrencyConverter;
import com.lumen.finance.entity.BillingAdjustment;
import com.lumen.finance.entity.BillingInvoice;
import com.lumen.finance.entity.BillingReconciliation;
import com.lumen.finance.event.ClosingAffectedEvent;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.BillingAdjustmentMapper;
import com.lumen.finance.mapper.BillingInvoiceMapper;
import com.lumen.finance.mapper.BillingReconciliationMapper;
import com.lumen.finance.service.AdjustmentService;
import com.lumen.finance.service.ExchangeRateService;
import com.lumen.finance.service.TaxRateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AdjustmentService 实现 (Iteration 4.3.2, PR4-T4.1).
 *
 * <p>状态机迁移 (与 V30 seed 对齐, 仅校验 {@code fromState -> toState}):
 * <ul>
 *   <li>{@code submit}:     {@code DRAFT      -> PENDING_L1},  写 {@code errorMsg="submitted..."}</li>
 *   <li>{@code l1Approve}: {@code PENDING_L1 -> PENDING_L2}, 写 L1 元信息</li>
 *   <li>{@code l1Reject}:  {@code PENDING_L1 -> REJECTED},  comment 必填, 写 L1 元信息</li>
 *   <li>{@code l2Approve}: {@code PENDING_L2 -> L2_APPROVED}, 写 L2 元信息; 同操作员拦截</li>
 *   <li>{@code l2Reject}:  {@code PENDING_L2 -> REJECTED},  comment 必填, 写 L2 元信息; 同操作员拦截</li>
 *   <li>{@code markPosted}: {@code L2_APPROVED -> POSTED},  errorMsg 留痕</li>
 * </ul>
 *
 * <p><b>create 入参校验</b>:
 * <ul>
 *   <li>{@code amount > 0} → {@code ADJUSTMENT_AMOUNT_INVALID}</li>
 *   <li>{@code reason.length() >= ADJUSTMENT_REASON_MIN_LEN} → {@code ADJUSTMENT_REASON_REQUIRED}</li>
 *   <li>源单据存在 + 同租户 → {@code INVOICE_NOT_FOUND}</li>
 *   <li>源单据 status == L2_APPROVED → {@code ADJUSTMENT_SOURCE_NOT_APPROVED}</li>
 *   <li>源单据 currency == adjustment.currency → {@code ADJUSTMENT_CURRENCY_MISMATCH}</li>
 * </ul>
 *
 * <p><b>create 计算</b>: amount 视为含税输入, 通过 {@link CurrencyConverter} 拆税;
 * taxRate 通过 {@link TaxRateService#getActiveRate}; 汇率继承源单据 {@code fx_rate_at_creation}
 * (由 submit 时锁定, 4.3.2 PR3 引入).
 *
 * <p><b>adjustment_no 生成</b>: 内联 {@code ADJ-{tenantId}-{epochMs}-{seq3}} (与 InvoiceService
 * generateInvoiceNo 同模式, 3 位随机数), 由 {@code uk_adj_no} 保证唯一.
 *
 * <p><b>异常包装</b>: 状态机底层抛 {@code com.lumen.extension.state.StateMachineException},
 * 已被 {@link #assertSmTransition} 包装为 {@code FinanceException(INVALID_STATE_TRANSITION)};
 * 并发 updateById 触发的 {@code OptimisticLockingFailureException} 同样包装为
 * {@code FinanceException(INVALID_STATE_TRANSITION)} (409 而非 500).
 *
 * <p><b>权限校验</b>: 在 controller 层 (T4.2) 用 {@code @PreAuthorize} 实现
 * ({@code finance:adjustment:create / submit / l1_approve / l2_approve});
 * service 只校验数据 + 状态机 + 同人约束.
 */
@Slf4j
@Service
public class AdjustmentServiceImpl implements AdjustmentService {

    /** adjustment_no 序列后缀位数 (3 位随机数, 与 InvoiceService generateInvoiceNo 对齐). */
    private static final int ADJ_NO_SEQ_LEN = 3;

    private final BillingAdjustmentMapper adjustmentMapper;
    private final BillingInvoiceMapper invoiceMapper;
    /** 4.5C-2.1 — 商家异议快照的权威来源 (t_reconciliation)。 */
    private final BillingReconciliationMapper reconMapper;
    private final StateMachineEngine stateMachine;
    private final ExchangeRateService exchangeRateService;
    private final TaxRateService taxRateService;
    private final ApplicationEventPublisher eventPublisher;

    /** 4.4 增量: 调账过账后用于推导 period (yyyy-MM) 的 formatter. */
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    public AdjustmentServiceImpl(BillingAdjustmentMapper adjustmentMapper,
                                 BillingInvoiceMapper invoiceMapper,
                                 BillingReconciliationMapper reconMapper,
                                 StateMachineEngine stateMachine,
                                 ExchangeRateService exchangeRateService,
                                 TaxRateService taxRateService,
                                 ApplicationEventPublisher eventPublisher) {
        this.adjustmentMapper = adjustmentMapper;
        this.invoiceMapper = invoiceMapper;
        this.reconMapper = reconMapper;
        this.stateMachine = stateMachine;
        this.exchangeRateService = exchangeRateService;
        this.taxRateService = taxRateService;
        this.eventPublisher = eventPublisher;
    }

    // ---------- create: insert DRAFT ----------

    @Override
    @Transactional
    public BillingAdjustment create(Long tenantId, Long sourceInvoiceId, String adjustmentType,
                                    BigDecimal amount, String reason, Long operatorId) {
        try {
            // 0. 调整类型校验
            if (!FinanceConstants.ADJ_TYPE_CREDIT.equals(adjustmentType)
                && !FinanceConstants.ADJ_TYPE_DEBIT.equals(adjustmentType)) {
                throw new FinanceException(FinanceErrorCode.EXPRESSION_INVALID,
                    "adjustmentType must be CREDIT or DEBIT, got: " + adjustmentType);
            }

            // 1. amount > 0
            if (amount == null || amount.signum() <= 0) {
                throw new FinanceException(FinanceErrorCode.ADJUSTMENT_AMOUNT_INVALID,
                    "Adjustment amount must be positive, got " + amount);
            }

            // 2. reason length >= 10
            if (reason == null
                || reason.length() < FinanceConstants.ADJUSTMENT_REASON_MIN_LEN
                || reason.isBlank()) {
                throw new FinanceException(FinanceErrorCode.ADJUSTMENT_REASON_REQUIRED,
                    "Adjustment reason must be at least "
                        + FinanceConstants.ADJUSTMENT_REASON_MIN_LEN + " chars, got "
                        + (reason == null ? 0 : reason.length()));
            }

            // 3. 源单据存在 + 同租户
            BillingInvoice source = invoiceMapper.selectById(sourceInvoiceId);
            if (source == null || !source.getTenantId().equals(tenantId)) {
                // 用现有 invoice 错误码 (与 InvoiceService 行为一致)
                throw new FinanceException(FinanceErrorCode.INVOICE_NOT_FOUND,
                    "Source invoice " + sourceInvoiceId + " not found for tenant " + tenantId);
            }

            // 4. 源单据必须 L2_APPROVED
            if (!FinanceConstants.INV_STATUS_L2_APPROVED.equals(source.getStatus())) {
                throw new FinanceException(FinanceErrorCode.ADJUSTMENT_SOURCE_NOT_APPROVED,
                    "Source invoice " + sourceInvoiceId + " status must be L2_APPROVED, got "
                        + source.getStatus());
            }

            // 5. currency 一致 (不支持跨币种调账)
            String currency = source.getCurrency();
            if (!FinanceConstants.CURRENCY_CNY.equals(currency)
                && !"USD".equals(currency)) {
                // 4.3.2 仅启用 CNY / USD, 其它币种显式拒绝
                throw new FinanceException(FinanceErrorCode.ADJUSTMENT_CURRENCY_MISMATCH,
                    "Unsupported adjustment currency: " + currency);
            }

            // 6. 取税率 + 计算税额/净额
            BigDecimal taxRate = taxRateService.getActiveRate(currency, LocalDate.now());
            BigDecimal taxAmount = CurrencyConverter.calculateTax(amount, taxRate);
            BigDecimal netAmount = amount.subtract(taxAmount)
                .setScale(CurrencyConverter.SCALE, java.math.RoundingMode.HALF_UP);

            // 7. 汇率继承源单据的 fx_rate_at_creation
            BigDecimal fxRate = source.getFxRateAtCreation() == null
                ? BigDecimal.ONE.setScale(CurrencyConverter.SCALE, java.math.RoundingMode.HALF_UP)
                : source.getFxRateAtCreation();

            // 8. 构造 + 插入
            BillingAdjustment adj = BillingAdjustment.builder()
                .tenantId(tenantId)
                .adjustmentNo(generateAdjustmentNo(tenantId))
                .sourceInvoiceId(sourceInvoiceId)
                .adjustmentType(adjustmentType)
                .amount(amount)
                .currency(currency)
                .fxRateAtCreation(fxRate)
                .taxRate(taxRate)
                .grossAmount(amount)
                .taxAmount(taxAmount)
                .netAmount(netAmount)
                .reason(reason)
                .status(FinanceConstants.ADJ_STATUS_DRAFT)
                .build();
            adjustmentMapper.insert(adj);

            log.info("create: tenant={} adjustmentId={} no={} sourceInvoiceId={} type={} amount={} {} operator={}",
                tenantId, adj.getId(), adj.getAdjustmentNo(),
                sourceInvoiceId, adjustmentType, amount, currency,
                operatorId == null ? "system" : operatorId);
            return adj;
        } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "Concurrent create detected for adjustment on invoice " + sourceInvoiceId);
        }
    }

    // ---------- submit: DRAFT -> PENDING_L1 ----------

    @Override
    @Transactional
    public BillingAdjustment submit(Long tenantId, Long adjustmentId, Long operatorId) {
        try {
            // 1. 取 adjustment, 校验 status=DRAFT
            BillingAdjustment adj = load(tenantId, adjustmentId);
            if (!FinanceConstants.ADJ_STATUS_DRAFT.equals(adj.getStatus())) {
                throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                    "Adjustment status must be DRAFT to submit, got " + adj.getStatus());
            }

            // 2. 状态机校验 DRAFT -> PENDING_L1
            assertSmTransition(
                FinanceConstants.SM_BILLING_ADJUSTMENT,
                FinanceConstants.ADJ_STATUS_DRAFT,
                FinanceConstants.ADJ_STATUS_PENDING_L1);

            // 3. 更新 adjustment: PENDING_L1 + errorMsg 留痕
            adj.setStatus(FinanceConstants.ADJ_STATUS_PENDING_L1);
            adj.setErrorMsg("submitted at " + LocalDateTime.now() + " by operator "
                + (operatorId == null ? "system" : operatorId));
            adjustmentMapper.updateById(adj);

            log.info("submit: tenant={} adjustmentId={} operator={}",
                tenantId, adjustmentId, operatorId == null ? "system" : operatorId);
            return adj;
        } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "Concurrent submit detected for adjustment " + adjustmentId);
        }
    }

    // ---------- l1Approve: PENDING_L1 -> PENDING_L2 ----------

    @Override
    @Transactional
    public BillingAdjustment l1Approve(Long tenantId, Long adjustmentId, Long operatorId, String comment) {
        try {
            // 1. 取 adjustment, 校验 status=PENDING_L1
            BillingAdjustment adj = load(tenantId, adjustmentId);
            if (!FinanceConstants.ADJ_STATUS_PENDING_L1.equals(adj.getStatus())) {
                throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                    "Adjustment status must be PENDING_L1 to l1-approve, got " + adj.getStatus());
            }

            // 2. 状态机校验 PENDING_L1 -> PENDING_L2
            assertSmTransition(
                FinanceConstants.SM_BILLING_ADJUSTMENT,
                FinanceConstants.ADJ_STATUS_PENDING_L1,
                FinanceConstants.ADJ_STATUS_PENDING_L2);

            // 3. 更新 adjustment: PENDING_L2 + L1 元信息
            adj.setStatus(FinanceConstants.ADJ_STATUS_PENDING_L2);
            adj.setL1ApproverId(operatorId);
            adj.setL1ApprovedAt(LocalDateTime.now());
            adj.setL1Comment(truncate(comment, FinanceConstants.ADJUSTMENT_L1_COMMENT_MAX_LEN));
            adjustmentMapper.updateById(adj);

            log.info("l1Approve: tenant={} adjustmentId={} operator={}",
                tenantId, adjustmentId, operatorId == null ? "system" : operatorId);
            return adj;
        } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "Concurrent l1Approve detected for adjustment " + adjustmentId);
        }
    }

    // ---------- l1Reject: PENDING_L1 -> REJECTED (comment 必填) ----------

    @Override
    @Transactional
    public BillingAdjustment l1Reject(Long tenantId, Long adjustmentId, Long operatorId, String comment) {
        try {
            // 0. 拒绝原因必填
            if (comment == null || comment.isBlank()) {
                throw new FinanceException(FinanceErrorCode.INVOICE_REJECT_REASON_REQUIRED,
                    "l1Reject requires non-blank comment");
            }

            // 1. 取 adjustment, 校验 status=PENDING_L1
            BillingAdjustment adj = load(tenantId, adjustmentId);
            if (!FinanceConstants.ADJ_STATUS_PENDING_L1.equals(adj.getStatus())) {
                throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                    "Adjustment status must be PENDING_L1 to l1-reject, got " + adj.getStatus());
            }

            // 2. 状态机校验 PENDING_L1 -> REJECTED
            assertSmTransition(
                FinanceConstants.SM_BILLING_ADJUSTMENT,
                FinanceConstants.ADJ_STATUS_PENDING_L1,
                FinanceConstants.ADJ_STATUS_REJECTED);

            // 3. 更新 adjustment: REJECTED + L1 元信息
            adj.setStatus(FinanceConstants.ADJ_STATUS_REJECTED);
            adj.setL1ApproverId(operatorId);
            adj.setL1ApprovedAt(LocalDateTime.now());
            adj.setL1Comment(truncate(comment, FinanceConstants.ADJUSTMENT_L1_COMMENT_MAX_LEN));
            adjustmentMapper.updateById(adj);

            log.info("l1Reject: tenant={} adjustmentId={} operator={} comment='{}'",
                tenantId, adjustmentId, operatorId == null ? "system" : operatorId,
                adj.getL1Comment());
            return adj;
        } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "Concurrent l1Reject detected for adjustment " + adjustmentId);
        }
    }

    // ---------- l2Approve: PENDING_L2 -> L2_APPROVED ----------

    @Override
    @Transactional
    public BillingAdjustment l2Approve(Long tenantId, Long adjustmentId, Long operatorId, String comment) {
        try {
            // 1. 取 adjustment, 校验 status=PENDING_L2
            BillingAdjustment adj = load(tenantId, adjustmentId);
            if (!FinanceConstants.ADJ_STATUS_PENDING_L2.equals(adj.getStatus())) {
                throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                    "Adjustment status must be PENDING_L2 to l2-approve, got " + adj.getStatus());
            }

            // 2. 硬约束: L1 与 L2 不能同一操作员
            if (adj.getL1ApproverId() != null && adj.getL1ApproverId().equals(operatorId)) {
                throw new FinanceException(FinanceErrorCode.L1_L2_SAME_OPERATOR,
                    "Same operator cannot L1 and L2 approve adjustment " + adjustmentId
                        + " (l1ApproverId=" + adj.getL1ApproverId() + ", operator=" + operatorId + ")");
            }

            // 3. 状态机校验 PENDING_L2 -> L2_APPROVED
            assertSmTransition(
                FinanceConstants.SM_BILLING_ADJUSTMENT,
                FinanceConstants.ADJ_STATUS_PENDING_L2,
                FinanceConstants.ADJ_STATUS_L2_APPROVED);

            // 4. 更新 adjustment: L2_APPROVED + L2 元信息
            adj.setStatus(FinanceConstants.ADJ_STATUS_L2_APPROVED);
            adj.setL2ApproverId(operatorId);
            adj.setL2ApprovedAt(LocalDateTime.now());
            adj.setL2Comment(truncate(comment, FinanceConstants.ADJUSTMENT_L2_COMMENT_MAX_LEN));
            adjustmentMapper.updateById(adj);

            log.info("l2Approve: tenant={} adjustmentId={} operator={}",
                tenantId, adjustmentId, operatorId == null ? "system" : operatorId);
            return adj;
        } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "Concurrent l2Approve detected for adjustment " + adjustmentId);
        }
    }

    // ---------- l2Reject: PENDING_L2 -> REJECTED (comment 必填) ----------

    @Override
    @Transactional
    public BillingAdjustment l2Reject(Long tenantId, Long adjustmentId, Long operatorId, String comment) {
        try {
            // 0. 拒绝原因必填
            if (comment == null || comment.isBlank()) {
                throw new FinanceException(FinanceErrorCode.INVOICE_REJECT_REASON_REQUIRED,
                    "l2Reject requires non-blank comment");
            }

            // 1. 取 adjustment, 校验 status=PENDING_L2
            BillingAdjustment adj = load(tenantId, adjustmentId);
            if (!FinanceConstants.ADJ_STATUS_PENDING_L2.equals(adj.getStatus())) {
                throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                    "Adjustment status must be PENDING_L2 to l2-reject, got " + adj.getStatus());
            }

            // 2. 硬约束: L1 与 L2 不能同一操作员
            if (adj.getL1ApproverId() != null && adj.getL1ApproverId().equals(operatorId)) {
                throw new FinanceException(FinanceErrorCode.L1_L2_SAME_OPERATOR,
                    "Same operator cannot L1 and L2 reject adjustment " + adjustmentId
                        + " (l1ApproverId=" + adj.getL1ApproverId() + ", operator=" + operatorId + ")");
            }

            // 3. 状态机校验 PENDING_L2 -> REJECTED
            assertSmTransition(
                FinanceConstants.SM_BILLING_ADJUSTMENT,
                FinanceConstants.ADJ_STATUS_PENDING_L2,
                FinanceConstants.ADJ_STATUS_REJECTED);

            // 4. 更新 adjustment: REJECTED + L2 元信息
            adj.setStatus(FinanceConstants.ADJ_STATUS_REJECTED);
            adj.setL2ApproverId(operatorId);
            adj.setL2ApprovedAt(LocalDateTime.now());
            adj.setL2Comment(truncate(comment, FinanceConstants.ADJUSTMENT_L2_COMMENT_MAX_LEN));
            adjustmentMapper.updateById(adj);

            log.info("l2Reject: tenant={} adjustmentId={} operator={} comment='{}'",
                tenantId, adjustmentId, operatorId == null ? "system" : operatorId,
                adj.getL2Comment());
            return adj;
        } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "Concurrent l2Reject detected for adjustment " + adjustmentId);
        }
    }

    // ---------- markPosted: L2_APPROVED -> POSTED ----------

    @Override
    @Transactional
    public BillingAdjustment markPosted(Long tenantId, Long adjustmentId, Long operatorId) {
        try {
            // 1. 取 adjustment, 校验 status=L2_APPROVED
            BillingAdjustment adj = load(tenantId, adjustmentId);
            if (!FinanceConstants.ADJ_STATUS_L2_APPROVED.equals(adj.getStatus())) {
                throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                    "Adjustment status must be L2_APPROVED to mark posted, got " + adj.getStatus());
            }

            // 2. 状态机校验 L2_APPROVED -> POSTED
            assertSmTransition(
                FinanceConstants.SM_BILLING_ADJUSTMENT,
                FinanceConstants.ADJ_STATUS_L2_APPROVED,
                FinanceConstants.ADJ_STATUS_POSTED);

            // 3. 更新 adjustment: POSTED + errorMsg 留痕
            adj.setStatus(FinanceConstants.ADJ_STATUS_POSTED);
            adj.setErrorMsg("posted at " + LocalDateTime.now() + " by operator "
                + (operatorId == null ? "system" : operatorId));
            adjustmentMapper.updateById(adj);

            // 4. 4.4 增量: 调账过账后通知月结重算快照 (异步, ClosingEventListener 消费)
            String period = LocalDateTime.now().format(PERIOD_FMT);
            eventPublisher.publishEvent(new ClosingAffectedEvent(
                this, adj.getTenantId(), period, adj.getId()));

            log.info("markPosted: tenant={} adjustmentId={} operator={}",
                tenantId, adjustmentId, operatorId == null ? "system" : operatorId);
            return adj;
        } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "Concurrent markPosted detected for adjustment " + adjustmentId);
        }
    }

    // ---------- get ----------

    /**
     * 按 id 取调账单 (只取数, 不回填商家异议快照).
     *
     * <p>写路径 (submit / l1Approve / ...) 走这里 —— 它们不展示异议, 回填只会白付
     * 一次 recon 查询。admin 的详情端点走公开的 {@link #get}, 那儿才需要快照。
     *
     * <p><b>商家提异议流程走不到这里</b>: {@code load()} 是 private, 跨类调不到。
     * {@code MerchantBillingViewServiceImpl.loadDisputableAdjustment} 调的是接口的
     * 公开 {@code get()}, 所以那条路径**仍然会带快照、仍然会多付一次 recon 查询**。
     *
     * <p>这是已知且刻意接受的: 商家提异议是低频路径, 一次带索引的单行 SELECT 成本
     * 可忽略; 而改成"商家侧自己查"就要在另一个类里复制一遍租户归属校验 —— 那正是
     * 跨租户 bug 最容易冒出来的地方, 收益与风险完全不成比例。数据不外泄:
     * 门户侧返回的是 {@code AdjustmentView} record, 不含这三个字段。
     */
    private BillingAdjustment load(Long tenantId, Long adjustmentId) {
        BillingAdjustment adj = adjustmentMapper.selectById(adjustmentId);
        if (adj == null || !adj.getTenantId().equals(tenantId)) {
            throw new FinanceException(FinanceErrorCode.ADJUSTMENT_NOT_FOUND);
        }
        return adj;
    }

    @Override
    public BillingAdjustment get(Long tenantId, Long adjustmentId) {
        BillingAdjustment adj = load(tenantId, adjustmentId);
        // 4.5C-2.1 — 把商家异议原话贴上去 (admin 审批界面要看到商家在争什么)。
        attachDisputes(tenantId, List.of(adj));
        return adj;
    }

    // ---------- list ----------

    @Override
    public List<BillingAdjustment> list(Long tenantId, String status, Boolean disputed,
                                        int page, int size) {
        int pn = page < 1 ? 1 : page;
        int ps = size < 1 ? 20 : Math.min(size, 200);
        Page<BillingAdjustment> pg = Page.of(pn, ps);

        // 4.5C-2.1 — disputed 过滤用「Java 侧 id 集 + in / notIn」, 不用 SQL 子查询:
        // TenantLineInnerInterceptor 对相关子查询的处理没验证过, 而 id 集这条路
        // 4.5C-2 的 ownedAdjustmentIds 已在生产跑通。
        //
        // id 集与快照是**两次独立查询**: 中间若有异议状态变化 (新增 / 软删), 过滤结果
        // 与快照可能短暂不一致 —— 这是良性的 ("快照为 null" 与 "此刻不可见" 语义一致,
        // 不会给出错误答案), 而且换来的好处是 attach 那次查询**有界** (≤ 页大小 200),
        // 而 id 集查询只取一列。合并成一次全列查询会把有界的那次变成无界 —— 不划算。
        Set<Long> disputedIds = null;
        if (disputed != null) {
            disputedIds = disputedAdjustmentIds(tenantId);
            if (Boolean.TRUE.equals(disputed) && disputedIds.isEmpty()) {
                // 一条异议都没有 → 直接空页。绝不能生成 id IN () 那种非法 SQL。
                return new ArrayList<>();
            }
            if (Boolean.FALSE.equals(disputed) && disputedIds.isEmpty()) {
                // 全都没有异议 → 不加任何过滤条件 (notIn 空集 = 全部排除, 语义反了)。
                disputedIds = null;
            }
        }

        LambdaQueryWrapper<BillingAdjustment> q = new LambdaQueryWrapper<BillingAdjustment>()
            .eq(BillingAdjustment::getTenantId, tenantId)
            .eq(status != null && !status.isBlank(),
                BillingAdjustment::getStatus, status);
        if (disputedIds != null) {
            if (Boolean.TRUE.equals(disputed)) {
                q.in(BillingAdjustment::getId, disputedIds);
            } else {
                q.notIn(BillingAdjustment::getId, disputedIds);
            }
        }
        q.orderByDesc(BillingAdjustment::getId);

        Page<BillingAdjustment> result = adjustmentMapper.selectPage(pg, q);
        List<BillingAdjustment> records = result.getRecords();
        // 返回值统一是可变 List (含空结果): 改动前 null 分支返回不可变的 List.of()、
        // 非空分支返回 new ArrayList<>() —— 两支可变性不一致。空结果那一支不能悄悄变成
        // 不可变的 List.of() —— 否则将来调用方 list(...).add(...) 会在空结果上抛
        // UnsupportedOperationException, 而非空结果上不抛, 行为不一致。
        // (MP 的 Page.getRecords() 实际不会返回 null, 所以现实中原本就是可变的;
        //  现在的写法只是把语义讲清楚, 并锁住这个契约。)
        if (records == null) {
            return new ArrayList<>();
        }
        List<BillingAdjustment> out = new ArrayList<>(records);
        if (out.isEmpty()) {
            return out;
        }
        attachDisputes(tenantId, out);
        return out;
    }

    /**
     * 4.5C-2.1 — 该租户下**有商家异议**的调账单 id 集。
     *
     * <p>「有没有异议」以 {@code dispute_at} 非空为准 —— V39 里 dispute_at 非空即代表
     * 该期已被异议过 (兼作幂等标记), 而 dispute_amount 可以为 NULL (商家「仅质疑不主张」)。
     * 所以不能用 dispute_reason 非空来判断。
     *
     * <p>deleted 只能手写: BillingReconciliation 实体没有 deleted 字段 (DDL 有)。
     *
     * <p><b>与 MerchantBillingViewServiceImpl.ownedAdjustmentIds 的区别</b>: 那里的 recon
     * 查询刻意**不按 deleted 过滤** —— 软删只是把 recon 行隐藏, 不是解除"这张调账单归谁"
     * 这个归属关系。本方法问的是"现在这条异议还算不算数", 语义不同, 所以按 deleted = 0 过滤。
     *
     * <p><b>IN 列表目前无上界</b>: 这个集合是**全租户**范围的 (所有商家、所有期间的
     * 异议调账单), 随时间单调增长, 没有分批。规模上来后需要回来做分批 —— 否则
     * {@code disputed=false} 生成的 {@code id NOT IN (N 个占位符)} 会让 MySQL 退化成
     * 扫描 + 逐行查表 (比 IN 更难优化)。对比先例
     * {@code MerchantBillingViewServiceImpl.ownedAdjustmentIds}: 它的集合按 counterparty
     * 收窄 (单个商家的量级, 几百), 压力小得多。
     *
     * <p><b>⚠️ 分批不是可选的性能优化, 是正确性约束</b>: 服务端 prepared statement 的
     * 占位符上限是 <b>65535</b>。一旦单租户的「有异议调账单」数超过这个量级,
     * {@code disputed=false} 生成的 {@code id NOT IN (...)} 会让 MySQL 直接抛
     * {@code Prepared statement contains too many placeholders} —— 整个请求 500,
     * 不是"慢"。到那个量级之前必须把 id 集分批 (或改成别的排除方式)。
     *
     * <p>另一条 recon 查询 ({@link #attachDisputes}) 是**有界**的 —— 只查本页的
     * adjustment id (≤ 页大小 200), 不受无上界问题影响。
     */
    private Set<Long> disputedAdjustmentIds(Long tenantId) {
        List<BillingReconciliation> rows = reconMapper.selectList(
                new LambdaQueryWrapper<BillingReconciliation>()
                        .select(BillingReconciliation::getAdjustmentId)
                        .eq(BillingReconciliation::getTenantId, tenantId)
                        .isNotNull(BillingReconciliation::getAdjustmentId)
                        .isNotNull(BillingReconciliation::getDisputeAt)
                        .apply("deleted = 0"));
        // 判空: 与 attachDisputes 同一风格。MyBatis 实际不返回 null (Mockito 默认给空
        // List), 但同文件两种风格会让人怀疑这里的 null 处理是不是漏了。
        if (rows == null || rows.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return rows.stream()
                .map(BillingReconciliation::getAdjustmentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 4.5C-2.1 — 把商家异议快照贴到调账单实体上 (瞬态字段, 不落库).
     *
     * <p>权威来源是 t_reconciliation 的 dispute_* 三列, 这里是只读快照。
     *
     * <p>同一个 adjustment 被多条 recon 认领 (脏数据) 时取 <b>dispute_at 最新</b>的那条:
     * 查询已按 dispute_at DESC 排序, 先到先得即可。
     *
     * <p><b>dispute_at 完全并列时结果是未定义的</b>: MySQL 对并列值的返回顺序没有保证,
     * 所以同一张调账单在两次刷新里可能贴出不同的商家原话。这是已知且刻意接受的 ——
     * 并列本身就是"这里有脏数据"的信号, 原话跳变能把它暴露出来; 加一个 tie-breaker
     * (如 {@code orderByDesc(id)}) 会让选择变得可复现, 但也会把这个信号抹平。规范
     * [N13-3] 明确要求"不做稳定挑一个的兜底", 所以维持现状; 若将来决定要可复现性,
     * 加 {@code orderByDesc(id)} 即可, 代价是失去这个信号。
     */
    private void attachDisputes(Long tenantId, List<BillingAdjustment> adjustments) {
        if (adjustments == null || adjustments.isEmpty()) {
            return;
        }
        List<Long> ids = adjustments.stream()
                .map(BillingAdjustment::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        List<BillingReconciliation> recons = reconMapper.selectList(
                new LambdaQueryWrapper<BillingReconciliation>()
                        .eq(BillingReconciliation::getTenantId, tenantId)
                        .in(BillingReconciliation::getAdjustmentId, ids)
                        .isNotNull(BillingReconciliation::getDisputeAt)
                        .apply("deleted = 0")
                        .orderByDesc(BillingReconciliation::getDisputeAt));
        if (recons == null || recons.isEmpty()) {
            return;
        }
        Map<Long, BillingReconciliation> latest = new HashMap<>();
        for (BillingReconciliation r : recons) {
            latest.putIfAbsent(r.getAdjustmentId(), r);   // 已按 dispute_at DESC 排序 → 先到先得
        }
        for (BillingAdjustment adj : adjustments) {
            BillingReconciliation r = latest.get(adj.getId());
            if (r == null) {
                continue;    // [N13-4] 无异议 → 3 个字段保持 null
            }
            adj.setDisputeReason(r.getDisputeReason());
            adj.setDisputeAmount(r.getDisputeAmount());
            adj.setDisputeAt(r.getDisputeAt());
        }
    }

    // ---------- helpers ----------

    /**
     * 包装 StateMachineException → FinanceException (INVALID_STATE_TRANSITION).
     * 失败时 GlobalExceptionHandler 返回 409 (state machine 迁移非法) 而非 500.
     */
    private void assertSmTransition(String smName, String fromState, String toState) {
        try {
            stateMachine.assertTransition(smName, fromState, toState);
        } catch (com.lumen.extension.state.StateMachineException sm) {
            throw new FinanceException(FinanceErrorCode.INVALID_STATE_TRANSITION,
                "State machine [" + smName + "] transition " + fromState + " → " + toState
                    + " not allowed: " + sm.getMessage());
        }
    }

    /**
     * 生成 adjustment no: {@code ADJ-{tenantId}-{epochMs}-{seq}}.
     *
     * <p>与 {@code AccrualServiceImpl.generateInvoiceNo} 同模式, 由 V29 表
     * {@code t_billing_adjustment.uk_adj_no (tenant_id, adjustment_no)} 保证唯一.
     */
    private String generateAdjustmentNo(Long tenantId) {
        long ts = System.currentTimeMillis();
        int seq = java.util.concurrent.ThreadLocalRandom.current().nextInt(0, 1000);
        return String.format("ADJ-%d-%d-%0" + ADJ_NO_SEQ_LEN + "d", tenantId, ts, seq);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
