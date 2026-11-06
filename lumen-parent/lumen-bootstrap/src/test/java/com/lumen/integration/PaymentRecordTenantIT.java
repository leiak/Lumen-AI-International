package com.lumen.integration;

import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.PaymentRecordDtos.CreateRequest;
import com.lumen.finance.entity.BillingPaymentRecord;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.service.PaymentRecordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4.5C-2.1 — 付款流水的租户隔离与错误码 (缺口 #8) 集成测试。
 *
 * <p><b>守的是什么。</b> {@code PaymentRecordServiceImpl.get} 在 4.5C-2.1 之前只按主键
 * 取行, 换个租户照样能取到别人的付款流水 —— 这在多租户 SaaS 里是数据泄漏, 不是"功能缺失"。
 * 现在它改成按 {@code (id, tenant_id, deleted = 0)} 取, 取不到就抛专用的
 * {@code PAYMENT_RECORD_NOT_FOUND} (404_007), 而不是通用的 {@code NOT_FOUND}。
 *
 * <p><b>为什么必须用真 Spring 上下文 + 真 MySQL。</b> 租户隔离有两道闸: service 里显式的
 * {@code .eq(tenantId)}、MyBatis-Plus 的 {@code TenantLineInnerInterceptor}
 * (按 {@code TenantContext} 自动追加 {@code tenant_id}), 外加软删过滤。本 IT 守的是
 * <b>端到端效果</b> —— 换个租户取不到别人的流水, 且取不到时抛的是专用的
 * {@code PAYMENT_RECORD_NOT_FOUND} (404_007) 而不是通用的 {@code NOT_FOUND}。
 * 单测里 mapper 是 mock 的, 根本不发 SQL, 租户隔离在那种环境里无从谈起。
 *
 * <p><b>但本 IT 定位不了是哪一道闸在起作用。</b> 实测: 把 {@code PaymentRecordServiceImpl.get}
 * 里那行显式 {@code .eq(getTenantId, tenantId)} 删掉, 或者把 {@code t_payment_record} 加进
 * {@code MybatisPlusConfig.ignoreTable} 豁免名单 (整道拆掉拦截器), 本 IT 仍然全绿 ——
 * 只要 {@code TenantContext} 有值, 拦截器就会替显式谓词把同一道门再关一遍, 两道闸互为冗余。
 * 真 SQL 暴露的是"租户隔离整体失效", 不是"某一道闸没了"。
 *
 * <p><b>要逐道闸定位, 看单测</b> {@code PaymentRecordServiceTest.get_filtersByIdTenantAndDeleted}
 * —— 它逐列断言 wrapper 的 SQL 片段 (条件列恰好是 {@code {id, tenant_id}}, 并断言列与值的
 * 绑定关系), 少一道或多一道都会红。别看这个 IT。
 *
 * <p><b>租户段 1_100_000+</b> —— 避开 {@link PaymentRecordCsvImportIT} 的 1_000_000+、
 * {@code ReconciliationIT} 的 1_600_000+、{@code MonthlyClosingIT} 的 1_500_000+。
 */
@DisplayName("PaymentRecord 租户隔离 IT")
class PaymentRecordTenantIT extends IntegrationBase {

    @Autowired private PaymentRecordService service;

    /**
     * Counter-based unique tenantId. 起始 1_100_000L 避开所有现有 IT 占用段。
     *
     * <p>每个测试独占两个租户 (A / B) —— 两条流水除了 tenant 之外完全一样
     * (同期间、同方向、同商家、同币种、同金额、同日期), 唯一键
     * {@code uk_payment_tenant_period_cpty} 里带着 tenant_id, 所以两条能并存。
     */
    private static final AtomicLong TENANT_SEQ = new AtomicLong(1_100_000L);
    private long tenantA;
    private long tenantB;

    @BeforeEach
    void setUp() {
        tenantA = TENANT_SEQ.incrementAndGet();
        tenantB = TENANT_SEQ.incrementAndGet();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * 在指定租户下种一条付款流水, 并把 {@code TenantContext} 切到该租户。
     *
     * <p>{@code TenantContext} 必须一起设: MyBatis-Plus 的
     * {@code TenantLineInnerInterceptor} 在 INSERT 时会按它注入 {@code tenant_id},
     * 不设的话默认落 tenant_id=0。
     */
    private BillingPaymentRecord seed(long tenantId) {
        TenantContext.set(tenantId);
        CreateRequest req = new CreateRequest();
        req.setPeriod("2026-10");
        req.setDirection(FinanceConstants.DIR_RECEIVABLE);
        req.setCounterpartyId(300L);
        req.setCounterpartyType(FinanceConstants.CP_CUSTOMER);
        req.setCurrency(FinanceConstants.CURRENCY_CNY);
        req.setAmount(new BigDecimal("100.00"));
        req.setPaymentDate(LocalDate.of(2026, 10, 15));
        req.setSourceRef("BANK-" + tenantId);
        return service.create(tenantId, 1L, req);
    }

    @Test
    @DisplayName("[N8-1] 别的租户取这条流水 → PAYMENT_RECORD_NOT_FOUND (404_007)")
    void get_otherTenant_throwsPaymentRecordNotFound() {
        BillingPaymentRecord created = seed(tenantA);

        TenantContext.set(tenantB);
        assertThatThrownBy(() -> service.get(tenantB, created.getId()))
                .isInstanceOf(FinanceException.class)
                .extracting("errorCode")
                .isEqualTo(FinanceErrorCode.PAYMENT_RECORD_NOT_FOUND);
    }

    @Test
    @DisplayName("[N8-2] 自己的租户取得到")
    void get_ownTenant_returnsRecord() {
        BillingPaymentRecord created = seed(tenantA);

        TenantContext.set(tenantA);
        BillingPaymentRecord got = service.get(tenantA, created.getId());

        assertThat(got.getId()).isEqualTo(created.getId());
        assertThat(got.getTenantId()).isEqualTo(tenantA);
    }

    @Test
    @DisplayName("[N8-1] list 只看得到自己租户的行")
    void list_onlyOwnTenant() {
        seed(tenantA);
        seed(tenantB);

        TenantContext.set(tenantA);
        assertThat(service.list(tenantA, "2026-10", FinanceConstants.DIR_RECEIVABLE, 1, 50))
                .hasSize(1);
    }
}
