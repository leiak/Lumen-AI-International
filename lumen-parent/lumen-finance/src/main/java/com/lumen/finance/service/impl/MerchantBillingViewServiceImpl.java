package com.lumen.finance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.portal.MerchantDtos.AdjustmentView;
import com.lumen.finance.dto.portal.MerchantDtos.InvoiceView;
import com.lumen.finance.dto.portal.MerchantDtos.PaymentView;
import com.lumen.finance.dto.portal.MerchantDtos.ReconView;
import com.lumen.finance.dto.portal.MerchantPage;
import com.lumen.finance.entity.*;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.*;
import com.lumen.finance.service.MerchantBillingViewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link MerchantBillingViewService} 实现 (Iteration 4.5C-2).
 *
 * <p>五个 mapper 注入, 全部走 {@code LambdaQueryWrapper} —— 本仓零 XML mapper,
 * 一行 mapper 接口都不用改。
 *
 * <p>所有查询都是<b>只读</b>的, 所以类上没有 {@code @Transactional}: 单条 SELECT
 * 自带一致性, 包一层事务只会多占一个连接。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantBillingViewServiceImpl implements MerchantBillingViewService {

    private final BillingInvoiceMapper invoiceMapper;
    private final BillingReconciliationMapper reconMapper;
    private final BillingPaymentRecordMapper paymentMapper;
    private final BillingAdjustmentMapper adjustmentMapper;
    private final BillingMonthlyClosingMapper closingMapper;

    /** 分页上限 —— 门户是给人看的, 不该允许一次拉十万行。 */
    private static final long MAX_PAGE_SIZE = 200L;

    /** 页码下限 —— 页码从 1 开始。 */
    private static final long MIN_PAGE = 1L;

    /** size 非法时的兜底值。 */
    private static final long DEFAULT_PAGE_SIZE = 20L;

    // ------------------------------------------------------------------ 对账

    @Override
    public MerchantPage<ReconView> listReconciliations(Long tenantId, Long counterpartyId,
                                                         String period, long page, long size) {
        validatePeriod(period);
        // 读 selectPage 的返回值而不是局部变量: MP 3.5.7 两者是同一个引用
        // (反编译确认 selectPage 既就地填充入参 page 又原样返回它), 生产环境
        // 两种读法等价。但单元测试里 mapper 是 mock, 返回的是**另一个** Page 实例 ——
        // 读局部变量会拿到未被填充的默认值, 测试就成了什么都不证明的空转。
        Page<BillingReconciliation> mp = reconMapper.selectPage(
                Page.of(clampPage(page), clamp(size)), reconScope(tenantId, counterpartyId, period));

        Set<String> closed = closedPeriods(tenantId, mp.getRecords().stream()
                .map(BillingReconciliation::getPeriod).collect(Collectors.toSet()));

        return MerchantPage.of(
                mp.getRecords().stream().map(r -> toReconView(r, closed.contains(r.getPeriod()))).toList(),
                mp.getTotal(), mp.getCurrent(), mp.getSize());
    }

    @Override
    public ReconView getReconciliation(Long tenantId, Long counterpartyId, Long id) {
        // id 是主键, 唯一 —— 所以 selectOne 安全, 不会因多行抛 TooManyResults。
        // 谓词里已含 counterparty_id, 别人的行根本不会返回, 返回 null 即 404。
        BillingReconciliation r = reconMapper.selectOne(
                reconScope(tenantId, counterpartyId, null).eq(BillingReconciliation::getId, id));
        if (r == null) {
            throw new FinanceException(FinanceErrorCode.RECONCILIATION_NOT_FOUND,
                    "Reconciliation " + id + " not found for counterparty " + counterpartyId);
        }
        return toReconView(r, isPeriodClosed(tenantId, r.getPeriod()));
    }

    /**
     * 对账表的软删除必须手写: 实体无 deleted 字段, 且全仓无全局逻辑删除配置。
     *
     * <p>{@code period} 的 if 守卫同样必需 — {@code eq(col, null)} 会生成
     * {@code period = null}, 那会让"不筛期间"变成"只看 period 为空的行", 静默返回空集。
     *
     * <p>{@code orderByDesc(id)} 不可省: 分页没有确定性排序时, MySQL 的返回顺序
     * 随扫描/索引/并发写而变, {@code LIMIT ? OFFSET ?} 的第 2 页可能与第 1 页
     * 重复或漏行 —— 商家翻页看到重复条目。id 是唯一列, 排序是全序。
     */
    private static LambdaQueryWrapper<BillingReconciliation> reconScope(Long tenantId, Long counterpartyId,
                                                                        String period) {
        LambdaQueryWrapper<BillingReconciliation> w = new LambdaQueryWrapper<BillingReconciliation>()
                .eq(BillingReconciliation::getTenantId, tenantId)
                .eq(BillingReconciliation::getCounterpartyId, counterpartyId)
                .eq(BillingReconciliation::getDirection, FinanceConstants.DIR_RECEIVABLE)
                .apply("deleted = 0");
        if (period != null) {
            w.eq(BillingReconciliation::getPeriod, period);
        }
        return w.orderByDesc(BillingReconciliation::getId);
    }

    /**
     * 闭期软删过滤必须手写, 理由与 {@code reconScope} 同源: {@code t_monthly_closing}
     * 的 DDL 有 {@code deleted TINYINT NOT NULL DEFAULT 0} (而且它进了唯一键
     * {@code uk_closing_tenant_period (tenant_id, period, deleted)}, 说明这张表是真的
     * 在用软删), 但 {@code BillingMonthlyClosing} 实体没有该字段, 全仓又没配
     * MyBatis-Plus 全局逻辑删除 —— 两者皆无, 只能自己写。
     *
     * <p>漏了它的后果不是"多查几行脏数据"而是<b>静默吞掉异议</b>: 一条被软删却
     * 仍是 {@code CLOSED} 的月结会把商家判成 {@code closed=true}, 而那正是 Task 3
     * 异议闸门 {@code RECON_NOT_DISPUTABLE} 的依据 —— 商家被无故禁止提异议, 且
     * 没有任何错误提示。
     */
    private boolean isPeriodClosed(Long tenantId, String period) {
        if (period == null) {
            return false;
        }
        BillingMonthlyClosing c = closingMapper.selectOne(new LambdaQueryWrapper<BillingMonthlyClosing>()
                .eq(BillingMonthlyClosing::getTenantId, tenantId)
                .eq(BillingMonthlyClosing::getPeriod, period)
                .eq(BillingMonthlyClosing::getStatus, FinanceConstants.CLOSING_STATUS_CLOSED)
                .apply("deleted = 0"));
        return c != null;
    }

    /**
     * 列表页一次性把本页涉及的期间全查出来, 避免每行查一次 (N+1)。
     *
     * <p>{@code deleted = 0} 同样必须手写, 理由见 {@link #isPeriodClosed}。
     */
    private Set<String> closedPeriods(Long tenantId, Set<String> periods) {
        if (periods.isEmpty()) {
            return Set.of();
        }
        return closingMapper.selectList(new LambdaQueryWrapper<BillingMonthlyClosing>()
                        .eq(BillingMonthlyClosing::getTenantId, tenantId)
                        .in(BillingMonthlyClosing::getPeriod, periods)
                        .eq(BillingMonthlyClosing::getStatus, FinanceConstants.CLOSING_STATUS_CLOSED)
                        .apply("deleted = 0"))
                .stream().map(BillingMonthlyClosing::getPeriod).collect(Collectors.toSet());
    }

    private static ReconView toReconView(BillingReconciliation r, boolean closed) {
        return new ReconView(r.getId(), r.getPeriod(), r.getCurrency(),
                r.getExpectedAmount(), r.getActualAmount(), r.getVarianceAmount(),
                r.getAdjustmentId(), r.getReconciliationStatus(), r.getRemark(),
                r.getDisputeAmount(), r.getDisputeReason(), r.getDisputeAt(), closed);
    }

    // ------------------------------------------------------------------ 发票

    @Override
    public MerchantPage<InvoiceView> listInvoices(Long tenantId, Long counterpartyId,
                                                   String period, String status, long page, long size) {
        validatePeriod(period);
        Page<BillingInvoice> mp = invoiceMapper.selectPage(
                Page.of(clampPage(page), clamp(size)), invoiceScope(tenantId, counterpartyId, period, status));

        return MerchantPage.of(mp.getRecords().stream().map(MerchantBillingViewServiceImpl::toInvoiceView).toList(),
                mp.getTotal(), mp.getCurrent(), mp.getSize());
    }

    @Override
    public InvoiceView getInvoice(Long tenantId, Long counterpartyId, Long id) {
        // id 是主键, 唯一 —— 所以 selectOne 安全, 不会因多行抛 TooManyResults。
        BillingInvoice inv = invoiceMapper.selectOne(invoiceScope(tenantId, counterpartyId, null, null)
                .eq(BillingInvoice::getId, id));
        if (inv == null) {
            throw new FinanceException(FinanceErrorCode.INVOICE_NOT_FOUND,
                    "Invoice " + id + " not found for counterparty " + counterpartyId);
        }
        return toInvoiceView(inv);
    }

    /**
     * 不手写 deleted —— BillingInvoice.deleted 有 @TableLogic, MP 会自动追加。
     *
     * <p>{@code orderByDesc(id)} 的理由同 {@code reconScope}: 没有确定性排序的分页
     * 会重复 / 漏行。
     */
    private static LambdaQueryWrapper<BillingInvoice> invoiceScope(Long tenantId, Long counterpartyId,
                                                                   String period, String status) {
        LambdaQueryWrapper<BillingInvoice> w = new LambdaQueryWrapper<BillingInvoice>()
                .eq(BillingInvoice::getTenantId, tenantId)
                .eq(BillingInvoice::getCounterpartyId, counterpartyId)
                .eq(BillingInvoice::getDirection, FinanceConstants.DIR_RECEIVABLE);
        // 发票表的期间是拆成 period_year / period_month 两列的 (对账表才是 CHAR(7))
        if (period != null) {
            YearMonth ym = YearMonth.parse(period);
            w.eq(BillingInvoice::getPeriodYear, ym.getYear())
             .eq(BillingInvoice::getPeriodMonth, ym.getMonthValue());
        }
        // 同样是 if 守卫: status 为 null 时若直接 eq, MP 生成 `status = null`,
        // "不筛状态"会变成"只看 status 空的行"。
        if (status != null) {
            w.eq(BillingInvoice::getStatus, status);
        }
        return w.orderByDesc(BillingInvoice::getId);
    }

    private static InvoiceView toInvoiceView(BillingInvoice i) {
        return new InvoiceView(i.getId(), i.getInvoiceNo(), periodOf(i.getPeriodYear(), i.getPeriodMonth()),
                i.getBusinessType(), i.getGrossAmount(), i.getTaxAmount(), i.getNetAmount(),
                i.getCurrency(), i.getFxRateAtCreation(), i.getStatus(), i.getCreatedAt(),
                // 审批意见文本保留 (商家要看到自己发票被驳回的理由), 审批人 id 裁掉
                i.getL1Comment(), i.getL2Comment());
    }

    private static String periodOf(Integer year, Integer month) {
        if (year == null || month == null) {
            return null;
        }
        return String.format("%04d-%02d", year, month);
    }

    // ------------------------------------------------------------------ 付款

    @Override
    public MerchantPage<PaymentView> listPayments(Long tenantId, Long counterpartyId,
                                                   String period, long page, long size) {
        validatePeriod(period);
        // LambdaQueryWrapper.eq(field, null) 会生成 `field = null` 而不是跳过 —— MP 的
        // 默认行为, 所以过滤条件一律用 if 守卫显式加。
        Page<BillingPaymentRecord> mp = paymentMapper.selectPage(
                Page.of(clampPage(page), clamp(size)), paymentScope(tenantId, counterpartyId, period));

        return MerchantPage.of(mp.getRecords().stream().map(MerchantBillingViewServiceImpl::toPaymentView).toList(),
                mp.getTotal(), mp.getCurrent(), mp.getSize());
    }

    private static LambdaQueryWrapper<BillingPaymentRecord> paymentScope(Long tenantId, Long counterpartyId,
                                                                           String period) {
        LambdaQueryWrapper<BillingPaymentRecord> w = new LambdaQueryWrapper<BillingPaymentRecord>()
                .eq(BillingPaymentRecord::getTenantId, tenantId)
                .eq(BillingPaymentRecord::getCounterpartyId, counterpartyId)
                .eq(BillingPaymentRecord::getDirection, FinanceConstants.DIR_RECEIVABLE)
                .apply("deleted = 0");
        if (period != null) {
            w.eq(BillingPaymentRecord::getPeriod, period);
        }
        return w.orderByDesc(BillingPaymentRecord::getId);
    }

    private static PaymentView toPaymentView(BillingPaymentRecord p) {
        return new PaymentView(p.getId(), p.getPeriod(), p.getCurrency(), p.getAmount(),
                p.getSourceRef(), p.getPaymentDate(), p.getSource(), p.getRemark());
    }

    // ------------------------------------------------------------------ 我的调账单

    @Override
    public MerchantPage<AdjustmentView> listMyAdjustments(Long tenantId, Long counterpartyId,
                                                          long page, long size) {
        Set<Long> ids = ownedAdjustmentIds(tenantId, counterpartyId);
        if (ids.isEmpty()) {
            // 回传**生效值**, 与有数据时的 MerchantPage.of(...) 保持同一语义 ——
            // 否则 size=500 时有调账单的商家拿到 200、没调账单的拿到 500。
            return MerchantPage.empty(clampPage(page), clamp(size));
        }
        Page<BillingAdjustment> mp = adjustmentMapper.selectPage(Page.of(clampPage(page), clamp(size)),
                new LambdaQueryWrapper<BillingAdjustment>()
                        .eq(BillingAdjustment::getTenantId, tenantId)
                        .in(BillingAdjustment::getId, ids)
                        // createdAt 不是唯一列, 必须再挂一个唯一 tie-breaker, 否则
                        // 同一毫秒创建的多行之间顺序仍未定义, 分页会重复 / 漏行。
                        .orderByDesc(BillingAdjustment::getCreatedAt)
                        .orderByDesc(BillingAdjustment::getId));

        return MerchantPage.of(mp.getRecords().stream().map(MerchantBillingViewServiceImpl::toAdjustmentView).toList(),
                mp.getTotal(), mp.getCurrent(), mp.getSize());
    }

    @Override
    public AdjustmentView getMyAdjustment(Long tenantId, Long counterpartyId, Long adjustmentId) {
        if (!ownedAdjustmentIds(tenantId, counterpartyId).contains(adjustmentId)) {
            throw new FinanceException(FinanceErrorCode.ADJUSTMENT_NOT_FOUND,
                    "Adjustment " + adjustmentId + " not found for counterparty " + counterpartyId);
        }
        // id 是主键, 唯一 —— 所以 selectOne 安全, 不会因多行抛 TooManyResults。
        BillingAdjustment a = adjustmentMapper.selectOne(new LambdaQueryWrapper<BillingAdjustment>()
                .eq(BillingAdjustment::getTenantId, tenantId)
                .eq(BillingAdjustment::getId, adjustmentId));
        if (a == null) {
            // 归属集里有但行没了 —— 并发删除 / 归属两跳之间被改。不泄漏区别, 仍按 404。
            throw new FinanceException(FinanceErrorCode.ADJUSTMENT_NOT_FOUND,
                    "Adjustment " + adjustmentId + " not found");
        }
        return toAdjustmentView(a);
    }

    /**
     * 两跳还原"属于该商家的调账单 id 集"。
     *
     * <p>跳 1: {@code t_reconciliation.adjustment_id} —— 月结建出来的自动调账单,
     * recon 本身带 {@code counterparty_id}, 一句就收得住。
     *
     * <p>跳 2: {@code t_billing_adjustment.source_invoice_id IN (该商家的发票 id)} ——
     * 先取该商家的发票 id 集, 再一次 IN 查完。
     *
     * <p><b>但 source_invoice_id 只是技术锚点, 不能单独当归属依据。</b>
     * {@code BillingAdjustmentServiceImpl.resolveSourceInvoiceId} 在
     * {@code counterpartyId} 非空时会收窄到该商家自己的 RECEIVABLE 发票, 但
     * <b>收窄不到时会退回租户级锚点</b> (tenant + status + currency, 取 id 最小的
     * 一张) —— 那张发票可能落在别家商家头上 (商家也可能压根没有自己的
     * L2_APPROVED 发票)。
     * 若不排除, B 走跳 2 就会看到 A 的调账单 (含 amount / variance, 以及 reason 里
     * 明写的 A 的 counterpartyId)。同租户多商家各自月结时命中面很大。
     *
     * <p>所以跳 2 的结果要减掉"已被别家 recon 认领"的那些 —— 那些归跳 1 管,
     * 归属以 recon 行的 counterparty_id 为准 (recon 行才是归属的权威来源,
     * source_invoice_id 只是锚点)。
     *
     * <p><b>认领表为什么可以跨商家</b>: 认领表要的是"这张调账单归谁", 所以必须是
     * 全租户 (不限 counterparty) 但只覆盖候选 adjustment id 的; 它带的是 id 与
     * counterparty_id, 不是金额 / 差异 / 原因等业务数据,
     * 拉进内存不构成信息泄漏 —— 它的唯一用途就是<b>排除</b>。这是与"先全量取 id
     * 再比对"那种写法在性质上的区别: 那一种是为了展示而把 id 全量拉进内存,
     * 这一种是为了拒绝而按候选集拉 —— 拉进来的只有 id 与 counterparty_id,
     * 且只服务于排除, 候选集为空时压根不查。
     *
     * <p><b>认领表为什么不按 direction 过滤</b>: 认领表只用于<b>排除</b>,
     * 方向不对称是刻意的 —— 跳 1 过滤 {@code direction=RECEIVABLE} (门户只看应收),
     * 认领表不过滤。写侧对 PAYABLE recon 的调账单会把锚点收窄到该商家自己的
     * RECEIVABLE 发票, 收窄不到时<b>退回租户级锚点</b>, 可能落在<b>别家商家</b>的
     * 发票上; 此时全靠认领表把它从锚点方的跳 2 里剔出去。若认领表加上
     * {@code direction=RECEIVABLE}, 这张调账单就会从跳 2 漏给锚点商家 ——
     * <b>那是真跨商家泄漏</b>。宁可多剔 (漏看) 不可少剔 (看错家)。
     *
     * <p><b>认领表为什么不按 deleted 过滤</b>: 软删只是把 recon 行隐藏, 不是解除
     * 归属关系。若认领表只取 {@code deleted = 0} 的 recon, 一条被软删的 recon 会让
     * 它的调账单重新从跳 2 漏出去 —— 归属关系必须按历史事实认定, 不能因为
     * "看不见了"就当作"没发生过"。
     *
     * <p><b>查询顺序</b>: 跳 1 → 查发票 id 集 → (非空时) 跳 2 候选 → 认领表(只查候选
     * adjustment id) → 过滤。认领表夹在跳 2 之后是有意的: 它只需要覆盖候选 id,
     * 语义不变但把"全租户拉取"降级成"候选集拉取"; 而且商家一张发票都没有时
     * 压根不会走到这一步。
     */
    private Set<Long> ownedAdjustmentIds(Long tenantId, Long counterpartyId) {
        Set<Long> ids = new LinkedHashSet<>();

        // 跳 1: 我自己的 recon 认领的调账单。软删的 recon 对商家不可见, 故过滤 deleted。
        reconMapper.selectList(new LambdaQueryWrapper<BillingReconciliation>()
                        .select(BillingReconciliation::getAdjustmentId)
                        .eq(BillingReconciliation::getTenantId, tenantId)
                        .eq(BillingReconciliation::getCounterpartyId, counterpartyId)
                        .eq(BillingReconciliation::getDirection, FinanceConstants.DIR_RECEIVABLE)
                        .apply("deleted = 0")
                        .isNotNull(BillingReconciliation::getAdjustmentId))
                .stream().map(BillingReconciliation::getAdjustmentId).forEach(ids::add);

        Set<Long> invoiceIds = invoiceMapper.selectList(new LambdaQueryWrapper<BillingInvoice>()
                        .select(BillingInvoice::getId)
                        .eq(BillingInvoice::getTenantId, tenantId)
                        .eq(BillingInvoice::getCounterpartyId, counterpartyId)
                        .eq(BillingInvoice::getDirection, FinanceConstants.DIR_RECEIVABLE))
                .stream().map(BillingInvoice::getId).collect(Collectors.toSet());

        // 一次 IN 查完, 不按发票逐张查 —— 商家一年几百张发票就是几百次往返。
        // IN 列表目前无上界: 商家发票数在本系统的量级下可接受, 分批会显著复杂化;
        // 商家数量增长后需要回来做分批。
        if (!invoiceIds.isEmpty()) {
            Set<Long> candidateIds = adjustmentMapper.selectList(new LambdaQueryWrapper<BillingAdjustment>()
                            .select(BillingAdjustment::getId)
                            .eq(BillingAdjustment::getTenantId, tenantId)
                            .in(BillingAdjustment::getSourceInvoiceId, invoiceIds))
                    .stream().map(BillingAdjustment::getId).collect(Collectors.toSet());

            if (!candidateIds.isEmpty()) {
                // 认领表: 候选 adjustment id -> counterpartyId。不按 counterparty 过滤
                // (要的就是全租户的认领关系), 不过滤 deleted (软删不解除归属),
                // 不过滤 direction (见上方 javadoc 的理由链 —— 加了就是真跨商家泄漏)。
                // 只覆盖候选 adjustment id: 认领表的唯一用途是"排除跳 2 的候选",
                // 所以按候选 id 收窄后语义完全不变, 但不必全租户拉取。
                // 用 HashMap 而不是 Collectors.toMap: toMap 遇到 null 的 value 会抛 NPE,
                // 而 counterpartyId 在数据异常时可能为 null —— 那种行归入 ambiguous,
                // 见下。
                Map<Long, Long> claimedBy = new HashMap<>();
                // 归属歧义的候选 id: 被不同 counterparty 认领, 或认领方的
                // counterparty_id 为 null。歧义 → 一律排除 (见下)。
                Set<Long> ambiguous = new HashSet<>();
                for (BillingReconciliation r : reconMapper.selectList(new LambdaQueryWrapper<BillingReconciliation>()
                        .select(BillingReconciliation::getAdjustmentId, BillingReconciliation::getCounterpartyId)
                        .eq(BillingReconciliation::getTenantId, tenantId)
                        .in(BillingReconciliation::getAdjustmentId, candidateIds))) {
                    Long adjustmentId = r.getAdjustmentId();
                    if (adjustmentId == null) {
                        continue;
                    }
                    Long owner = r.getCounterpartyId();
                    if (owner == null) {
                        // 没人能保护锚点方 —— 数据异常。与其猜一个归属, 不如排除。
                        ambiguous.add(adjustmentId);
                        continue;
                    }
                    Long previous = claimedBy.putIfAbsent(adjustmentId, owner);
                    if (previous != null && !previous.equals(owner)) {
                        // 同一张调账单被两个不同 counterparty 认领: 归属不可确定,
                        // 顺序无关才是真确定 —— 一律排除, 不"稳定地挑一个"。
                        ambiguous.add(adjustmentId);
                    }
                }
                if (!ambiguous.isEmpty()) {
                    log.warn("ownedAdjustmentIds: adjustment 归属歧义, 已从跳 2 排除, tenant={} counterparty={} ids={}",
                            tenantId, counterpartyId, ambiguous);
                }

                candidateIds.stream()
                        // 认领给别人 (别家 recon 认领) 的调账单剔除 —— 那是锚点串了,
                        // 归属属于对方, 走对方的跳 1。认领给我或没人认领的才留。
                        // 歧义的一律剔除 —— 宁可漏看不可看错家。
                        // 注意 owner == null 现在的语义已经收敛: 它只表示
                        // "压根不在认领表里"(无人认领), 不再兼表"认领方为 null"
                        // (那种行已先进 ambiguous)。
                        .filter(id -> {
                            if (ambiguous.contains(id)) {
                                return false;
                            }
                            Long owner = claimedBy.get(id);
                            return owner == null || owner.equals(counterpartyId);
                        })
                        .forEach(ids::add);
            }
        }

        return ids;
    }

    private static AdjustmentView toAdjustmentView(BillingAdjustment a) {
        return new AdjustmentView(a.getId(), a.getAdjustmentNo(), a.getAdjustmentType(), a.getAmount(),
                a.getCurrency(), a.getGrossAmount(), a.getTaxAmount(), a.getNetAmount(),
                a.getStatus(), a.getReason(), a.getCreatedAt(), a.getL1Comment(), a.getL2Comment());
    }

    // ------------------------------------------------------------------ 杂项

    /**
     * 期间格式校验 —— <b>period 可为 null (表示不过滤), 空串或格式非法一律 400</b>。
     *
     * <p>注意: 仓内 {@code PaymentRecordServiceImpl.validatePeriod} <b>同名但语义不同</b>
     * —— 那边的 null 是<b>非法</b>(付款记录必须归属某个期间), 这边的 null 是"不过滤"。
     * 不要因为同名就把两边的语义混起来 (也不要改名: 计划文档 Task 3 引用了本方法)。
     *
     * <p>放在 service 而不是只靠 controller 的 @Pattern, 是因为门户之外的调用方
     * (未来的报表 / 定时任务) 也会进来, 而 {@code YearMonth.parse} 遇到
     * {@code 2026-13} / {@code abc} 会抛 {@code DateTimeParseException} ——
     * 那不是 {@code FinanceException}, 门户与全局 handler 都没有它的分支,
     * 最终落 catch-all 变成 HTTP 500。商家手滑不该看到"服务器内部错误"。
     *
     * <p>recon / payment 的 period 是 CHAR(7) 等值比, 不校验的话 {@code "abc"}
     * 会静默返回空集 —— 商家以为"这期间没数据"而不是"你参数写错了"。
     */
    private static void validatePeriod(String period) {
        if (period != null && !period.matches(FinanceConstants.PERIOD_PATTERN)) {
            throw new FinanceException(FinanceErrorCode.BAD_REQUEST,
                    "Invalid period format, expected YYYY-MM, got: " + period);
        }
    }

    /**
     * 分页上限兜底: size &lt;= 0 给 20, 超过 {@link #MAX_PAGE_SIZE} 截断。
     */
    private static long clamp(long size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * 页码兜底: 小于 1 一律当第 1 页。{@code Page.of(...)} 内部也做同样归一
     * (current &lt;= 1 时保持 1), 这里显式做一遍, 是为了让"没有数据"的那条分支
     * ({@link MerchantPage#empty}) 与"有数据"的分支回传同一套生效值。
     */
    private static long clampPage(long page) {
        return page < MIN_PAGE ? MIN_PAGE : page;
    }
}
