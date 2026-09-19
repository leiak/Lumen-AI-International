package com.lumen.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.common.tenant.TenantContext;
import com.lumen.extension.approval.ApprovalException;
import com.lumen.extension.approval.ApprovalRecord;
import com.lumen.extension.approval.ApprovalRecordMapper;
import com.lumen.extension.approval.ApprovalService;
import com.lumen.extension.approval.SubmitApprovalRequest;
import com.lumen.extension.outbox.OutboxDispatcher;
import com.lumen.masterdata.entity.SysCountry;
import com.lumen.masterdata.mapper.SysCountryMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 集成测试：审批流端到端（submit → approve → 应用变更）。
 *
 * <ol>
 *   <li>{@code submit_approve_emitsApprovedEvent_andAppliesChanges} —
 *       submit 后 PENDING；approve 后 APPROVED + outbox 投递
 *       {@code country.edit_approved} 事件 → 顶层 {@link CountryEditApprovedTestListener}
 *       收到事件 + 生产 {@code CountryEditApprovedListener} 把 nameCn/nameEn 写入 sys_country。</li>
 *   <li>{@code approve_byNonAdminRole_throwsNoPermission} —
 *       V6 fix_permissions.sql 给 role_id=1 (ADMIN) 配了 approval:approve；
 *       user 2 在 V2 rbac.sql 是普通 USER，没 ADMIN 角色 → ApprovalException.noPermission()。</li>
 *   <li>{@code reject_terminatesApproval} —
 *       reject 后 REJECTED；再 approve 应抛 ApprovalException.notPending。</li>
 *   <li>{@code withdraw_onlyByApplicant} —
 *       非申请人撤回 → ApprovalException.notApplicant；申请人撤回 → WITHDRAWN。</li>
 * </ol>
 *
 * <p>踩坑：
 * <ul>
 *   <li>IntegrationBase 已带 @SpringBootTest，子类不要再加。</li>
 *   <li>SysCountry 字段名是 {@code code}（不是 {@code countryCode}）。</li>
 *   <li>sys_country.code 是 VARCHAR(8) + 唯一索引 (tenant_id, code)，必须用
 *       短唯一值（nanoTime 末 6 位 + 静态计数器）。</li>
 *   <li>TenantContext.set(1L) 让 BaseEntity 把 sys_country.tenant_id 自动填 1，
 *       与 V2 rbac.sql 种子一致（admin user/SUPER_ADMIN role/user_role/role_permission
 *       都 tenant_id=1）。审批服务 hasRole 路径上的 sys_role/sys_user_role 同样
 *       tenant_id=1，permissionService.userHasRole(...) 才能找到 SUPER_ADMIN。</li>
 *   <li>TestListener 顶层 @Component + @Import（nested 在 @SpringBootTest
 *       下 @EventListener 偶发漏注册）。</li>
 * </ul>
 */
@Import(CountryEditApprovedTestListener.class)
class ApprovalFlowIT extends IntegrationBase {

    @Autowired ApprovalService approvalService;
    @Autowired ApprovalRecordMapper approvalMapper;
    @Autowired SysCountryMapper countryMapper;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired CountryEditApprovedTestListener testListener;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void submit_approve_emitsApprovedEvent_andAppliesChanges() throws Exception {
        SysCountry c = createCountry("DRAFT");
        TenantContext.set(1L);
        try {
            testListener.clear();

            // submit: applicant=100, bizId=countryId, payload=nameCn/nameEn 变更新值
            Map<String, Object> changes = new HashMap<>();
            changes.put("nameCn", "更新名");
            changes.put("nameEn", "Updated Name");
            SubmitApprovalRequest req = new SubmitApprovalRequest(
                    "country_edit", String.valueOf(c.getId()), 100L, changes, "申请编辑"
            );
            ApprovalRecord record = approvalService.submit(req);
            assertThat(record.getStatus()).isEqualTo("PENDING");

            // approve: user 1 = SUPER_ADMIN (V2 rbac.sql seeds role_id=1, code='SUPER_ADMIN',
            // user_role row (1,1,1) 把 user 1 绑给 role 1)
            approvalService.approve(record.getId(), 1L, "OK");

            ApprovalRecord reloaded = approvalMapper.selectById(record.getId());
            assertThat(reloaded.getStatus()).isEqualTo("APPROVED");
            assertThat(reloaded.getApproverId()).isEqualTo(1L);
            assertThat(reloaded.getComment()).isEqualTo("OK");

            // CountryEditApprovedEvent.aggregateId() 返回 countryId.toString()，
            // 所以 outbox 行 aggregate_id = countryId（不是 approvalId）。
            // 把该行的 next_retry_at 提前到过去 + 手动 dispatch，绕开 @Scheduled 2s 轮询，
            // 让测试稳定。
            com.lumen.extension.outbox.EventOutbox row = outboxMapper().selectList(
                    new LambdaQueryWrapper<com.lumen.extension.outbox.EventOutbox>()
                            .eq(com.lumen.extension.outbox.EventOutbox::getAggregateId, String.valueOf(c.getId()))
                            .eq(com.lumen.extension.outbox.EventOutbox::getEventType, "country.edit_approved")
            ).get(0);
            row.setNextRetryAt(java.time.LocalDateTime.now().minusSeconds(1));
            outboxMapper().updateById(row);

            testListener.clear();
            new TransactionTemplate(txManager).executeWithoutResult(s -> dispatcher.dispatch());

            // 等测试 listener 收到事件（生产 listener @Async on outboxExecutor 也并行执行，
            // 所以这里 received 与实际 country 更新大致同时发生）
            long start = System.currentTimeMillis();
            while (testListener.getReceived().isEmpty()
                    && System.currentTimeMillis() - start < 5_000) {
                Thread.sleep(100);
            }
            assertThat(testListener.getReceived())
                    .as("CountryEditApprovedEvent must be delivered via outbox dispatcher")
                    .isNotEmpty();

            // 等生产 listener 把 nameCn 写入（异步；最长 5s）
            SysCountry finalCountry;
            long start2 = System.currentTimeMillis();
            do {
                finalCountry = countryMapper.selectById(c.getId());
                if ("更新名".equals(finalCountry.getNameCn())) break;
                Thread.sleep(100);
            } while (System.currentTimeMillis() - start2 < 5_000);

            assertThat(finalCountry.getNameCn()).isEqualTo("更新名");
            assertThat(finalCountry.getNameEn()).isEqualTo("Updated Name");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void approve_byNonAdminRole_throwsNoPermission() {
        SysCountry c = createCountry("DRAFT");
        TenantContext.set(1L);
        try {
            ApprovalRecord record = approvalService.submit(new SubmitApprovalRequest(
                    "country_edit", String.valueOf(c.getId()), 100L, new HashMap<>(), null
            ));
            // user 2 在 V2 rbac.sql 没有 user_role 记录（只有 user 1 有 SUPER_ADMIN）
            // → ApprovalService.hasRole 返回 false → ApprovalException.noPermission()
            assertThatThrownBy(() -> approvalService.approve(record.getId(), 2L, null))
                    .isInstanceOf(ApprovalException.class);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void reject_terminatesApproval() {
        SysCountry c = createCountry("DRAFT");
        TenantContext.set(1L);
        try {
            ApprovalRecord record = approvalService.submit(new SubmitApprovalRequest(
                    "country_edit", String.valueOf(c.getId()), 100L, new HashMap<>(), null
            ));

            approvalService.reject(record.getId(), 1L, "data wrong");

            ApprovalRecord reloaded = approvalMapper.selectById(record.getId());
            assertThat(reloaded.getStatus()).isEqualTo("REJECTED");
            assertThat(reloaded.getApproverId()).isEqualTo(1L);
            assertThat(reloaded.getComment()).isEqualTo("data wrong");

            // 再 approve 应抛 NOT_PENDING（ApprovalException.notPending）
            assertThatThrownBy(() -> approvalService.approve(record.getId(), 1L, null))
                    .isInstanceOf(ApprovalException.class);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void withdraw_onlyByApplicant() {
        SysCountry c = createCountry("DRAFT");
        TenantContext.set(1L);
        try {
            ApprovalRecord record = approvalService.submit(new SubmitApprovalRequest(
                    "country_edit", String.valueOf(c.getId()), 100L, new HashMap<>(), null
            ));

            // 非申请人撤回 → notApplicant
            assertThatThrownBy(() -> approvalService.withdraw(record.getId(), 999L))
                    .isInstanceOf(ApprovalException.class);

            // 申请人撤回 OK
            approvalService.withdraw(record.getId(), 100L);
            assertThat(approvalMapper.selectById(record.getId()).getStatus()).isEqualTo("WITHDRAWN");
        } finally {
            TenantContext.clear();
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private com.lumen.extension.outbox.EventOutboxMapper outboxMapper() {
        // lazy autowire via ApplicationContext —— @Autowired on field would also work but
        // this avoids cluttering the class with another field
        return testApplicationContext.getBean(com.lumen.extension.outbox.EventOutboxMapper.class);
    }

    @Autowired private org.springframework.context.ApplicationContext testApplicationContext;

    /**
     * 并发 approve：两个 SUPER_ADMIN 同时 approve 同一 record。
     * <p>ApprovalService.approve 经典 check-then-act：
     *   mapper.selectById → 校验 status=PENDING → mapper.updateById → eventBus.publish。
     * 两个线程若都先读到 PENDING 状态，没保护就会写两次 APPROVED + 发两次事件。
     * <p>t_approval_record.version + MP OptimisticLockerInnerInterceptor 会
     * 给 UPDATE 加 {@code WHERE id=? AND version=?}，第二个线程的 updateById
     * 拿到 rowsAffected=0 → 抛 {@code OptimisticLockingFailureException} →
     * 事务回滚（连带它刚 insert 的 outbox row 一起回滚）。
     * <p>期望：恰好 1 条 {@code country.edit_approved} 事件，2 个线程中 1 个成功 1 个抛错。
     */
    @Test
    void concurrentApprove_byTwoAdmins_onlyOneSucceeds_oneOutboxEvent() throws Exception {
        SysCountry c = createCountry("DRAFT");
        TenantContext.set(1L);
        try {
            ApprovalRecord record = approvalService.submit(new SubmitApprovalRequest(
                    "country_edit", String.valueOf(c.getId()), 100L, new HashMap<>(), null
            ));

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService es = Executors.newFixedThreadPool(2);

            Callable<String> task = () -> {
                TenantContext.set(1L);  // mapper 拦截器依赖线程上下文
                ready.countDown();
                start.await();
                try {
                    approvalService.approve(record.getId(), 1L, "ok");
                    return "ok";
                } catch (Exception e) {
                    return e.getClass().getSimpleName();
                } finally {
                    TenantContext.clear();
                }
            };
            Future<String> fa = es.submit(task);
            Future<String> fb = es.submit(task);
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            String ra = fa.get(5, TimeUnit.SECONDS);
            String rb = fb.get(5, TimeUnit.SECONDS);
            es.shutdown();

            long okCount = java.util.stream.Stream.of(ra, rb).filter("ok"::equals).count();
            long failCount = java.util.stream.Stream.of(ra, rb).filter(s -> !"ok".equals(s)).count();
            assertThat(okCount).as("exactly one thread's approve() returns OK").isEqualTo(1);
            assertThat(failCount).as("exactly one thread's approve() throws (optimistic lock or notPending)").isEqualTo(1);

            // 审批记录最终状态：APPROVED（只有一个人成功）
            ApprovalRecord reloaded = approvalMapper.selectById(record.getId());
            assertThat(reloaded.getStatus()).isEqualTo("APPROVED");
            assertThat(reloaded.getApproverId()).isEqualTo(1L);

            // 恰好 1 条 country.edit_approved 事件（失败线程的 outbox row 随事务回滚）
            long approvedEvents = outboxMapper().selectCount(
                    new LambdaQueryWrapper<com.lumen.extension.outbox.EventOutbox>()
                            .eq(com.lumen.extension.outbox.EventOutbox::getAggregateId, String.valueOf(c.getId()))
                            .eq(com.lumen.extension.outbox.EventOutbox::getEventType, "country.edit_approved")
            );
            assertThat(approvedEvents)
                    .as("after race: exactly one country.edit_approved event (loser's tx rolled back)")
                    .isEqualTo(1);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 直接 insert 一条 SysCountry。sys_country.code VARCHAR(8) + 唯一索引 (tenant_id, code)，
     * 用 nanoTime 末 6 位 + 静态计数器保证唯一性 + 长度。
     */
    private SysCountry createCountry(String status) {
        SysCountry c = new SysCountry();
        c.setCode("T" + String.format("%06d", (System.nanoTime() % 1_000_000) + counter.getAndIncrement()));
        c.setNameCn("测试国");
        c.setNameEn("Test Country");
        c.setStatus(status);
        TenantContext.set(1L);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> countryMapper.insert(c));
        TenantContext.clear();
        return c;
    }

    private static final AtomicLong counter = new AtomicLong();
}
