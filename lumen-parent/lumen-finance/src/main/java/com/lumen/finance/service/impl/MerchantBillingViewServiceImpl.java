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
import java.util.LinkedHashSet;
import java.util.List;
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

    // ------------------------------------------------------------------ 对账

    @Override
    public MerchantPage<ReconView> listReconciliations(Long tenantId, Long counterpartyId,
                                                         String period, long page, long size) {
        // 取 selectPage 的返回值而不是复用自己 new 的那个 Page —— 真实 MP 会就地填充
        // 传进去的实例, 但接口契约上被填充的是"返回值", 只有读返回值才对得上契约。
        Page<BillingReconciliation> mp = reconMapper.selectPage(
                Page.of(page, clamp(size)), reconScope(tenantId, counterpartyId, period));

        Set<String> closed = closedPeriods(tenantId, mp.getRecords().stream()
                .map(BillingReconciliation::getPeriod).collect(Collectors.toSet()));

        return MerchantPage.of(
                mp.getRecords().stream().map(r -> toReconView(r, closed.contains(r.getPeriod()))).toList(),
                mp.getTotal(), mp.getCurrent(), mp.getSize());
    }

    @Override
    public ReconView getReconciliation(Long tenantId, Long counterpartyId, Long id) {
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
        return w;
    }

    private boolean isPeriodClosed(Long tenantId, String period) {
        if (period == null) {
            return false;
        }
        BillingMonthlyClosing c = closingMapper.selectOne(new LambdaQueryWrapper<BillingMonthlyClosing>()
                .eq(BillingMonthlyClosing::getTenantId, tenantId)
                .eq(BillingMonthlyClosing::getPeriod, period)
                .eq(BillingMonthlyClosing::getStatus, FinanceConstants.CLOSING_STATUS_CLOSED));
        return c != null;
    }

    /** 列表页一次性把本页涉及的期间全查出来, 避免每行查一次 (N+1)。 */
    private Set<String> closedPeriods(Long tenantId, Set<String> periods) {
        if (periods.isEmpty()) {
            return Set.of();
        }
        return closingMapper.selectList(new LambdaQueryWrapper<BillingMonthlyClosing>()
                        .eq(BillingMonthlyClosing::getTenantId, tenantId)
                        .in(BillingMonthlyClosing::getPeriod, periods)
                        .eq(BillingMonthlyClosing::getStatus, FinanceConstants.CLOSING_STATUS_CLOSED))
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
        Page<BillingInvoice> mp = invoiceMapper.selectPage(
                Page.of(page, clamp(size)), invoiceScope(tenantId, counterpartyId, period, status));

        return MerchantPage.of(mp.getRecords().stream().map(MerchantBillingViewServiceImpl::toInvoiceView).toList(),
                mp.getTotal(), mp.getCurrent(), mp.getSize());
    }

    @Override
    public InvoiceView getInvoice(Long tenantId, Long counterpartyId, Long id) {
        BillingInvoice inv = invoiceMapper.selectOne(invoiceScope(tenantId, counterpartyId, null, null)
                .eq(BillingInvoice::getId, id));
        if (inv == null) {
            throw new FinanceException(FinanceErrorCode.INVOICE_NOT_FOUND,
                    "Invoice " + id + " not found for counterparty " + counterpartyId);
        }
        return toInvoiceView(inv);
    }

    /** 不手写 deleted —— BillingInvoice.deleted 有 @TableLogic, MP 会自动追加。 */
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
        return w;
    }

    private static InvoiceView toInvoiceView(BillingInvoice i) {
        return new InvoiceView(i.getId(), i.getInvoiceNo(), periodOf(i.getPeriodYear(), i.getPeriodMonth()),
                i.getBusinessType(), i.getGrossAmount(), i.getTaxAmount(), i.getNetAmount(),
                i.getCurrency(), i.getFxRateAtCreation(), i.getStatus(), i.getCreatedAt());
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
        // LambdaQueryWrapper.eq(field, null) 会生成 `field = null` 而不是跳过 —— MP 的
        // 默认行为, 所以过滤条件一律用 if 守卫显式加。
        Page<BillingPaymentRecord> mp = paymentMapper.selectPage(
                Page.of(page, clamp(size)), paymentScope(tenantId, counterpartyId, period));

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
        return w;
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
            return MerchantPage.empty(page, size);
        }
        Page<BillingAdjustment> mp = adjustmentMapper.selectPage(Page.of(page, clamp(size)),
                new LambdaQueryWrapper<BillingAdjustment>()
                        .eq(BillingAdjustment::getTenantId, tenantId)
                        .in(BillingAdjustment::getId, ids)
                        .orderByDesc(BillingAdjustment::getCreatedAt));

        return MerchantPage.of(mp.getRecords().stream().map(MerchantBillingViewServiceImpl::toAdjustmentView).toList(),
                mp.getTotal(), mp.getCurrent(), mp.getSize());
    }

    @Override
    public AdjustmentView getMyAdjustment(Long tenantId, Long counterpartyId, Long adjustmentId) {
        if (!ownedAdjustmentIds(tenantId, counterpartyId).contains(adjustmentId)) {
            throw new FinanceException(FinanceErrorCode.ADJUSTMENT_NOT_FOUND,
                    "Adjustment " + adjustmentId + " not found for counterparty " + counterpartyId);
        }
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
     * <p>跳 2: {@code t_billing_adjustment.source_invoice_id IN (该商家的发票 id) } ——
     * 先取该商家的发票 id 集, 再一次 IN 查完。
     *
     * <p>两张表都是先按商家过滤再取 id, 所以这里不存在"先全量取 id 再比对"的写法 ——
     * 那样会把别家商家的 id 短暂拉进内存。
     */
    private Set<Long> ownedAdjustmentIds(Long tenantId, Long counterpartyId) {
        Set<Long> ids = new LinkedHashSet<>();

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
        if (!invoiceIds.isEmpty()) {
            adjustmentMapper.selectList(new LambdaQueryWrapper<BillingAdjustment>()
                            .select(BillingAdjustment::getId)
                            .eq(BillingAdjustment::getTenantId, tenantId)
                            .in(BillingAdjustment::getSourceInvoiceId, invoiceIds))
                    .stream().map(BillingAdjustment::getId).forEach(ids::add);
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
     * 分页上限兜底: size &lt;= 0 给 20, 超过 {@link #MAX_PAGE_SIZE} 截断。
     */
    private static long clamp(long size) {
        if (size <= 0) {
            return 20L;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}