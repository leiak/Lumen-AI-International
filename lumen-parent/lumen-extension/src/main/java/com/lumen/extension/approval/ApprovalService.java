package com.lumen.extension.approval;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lumen.common.tenant.TenantContext;
import com.lumen.extension.outbox.EventBus;
import com.lumen.extension.outbox.events.CountryEditApprovedEvent;
import com.lumen.extension.outbox.events.CountryEditRejectedEvent;
import com.lumen.extension.outbox.events.CountryEditSubmittedEvent;
import com.lumen.rbac.service.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {
    private final ApprovalRecordMapper mapper;
    private final ApprovalRouter router;
    private final EventBus eventBus;
    private final PermissionService permissionService;

    @Transactional
    public ApprovalRecord submit(SubmitApprovalRequest req) {
        if (!router.knowsBizType(req.getBizType())) {
            throw ApprovalException.unknownBizType(req.getBizType());
        }
        List<ApprovalChainConfig> chain = router.getChain(req.getBizType());
        ApprovalChainConfig level = chain.get(0);  // v1 单级

        ApprovalRecord record = new ApprovalRecord();
        record.setBizType(req.getBizType());
        record.setBizId(req.getBizId());
        record.setTenantId(TenantContext.get());
        record.setLevel(level.level());
        record.setStatus(ApprovalStatus.PENDING.name());
        record.setApplicantId(req.getApplicantId());
        record.setApprovalRole(level.role());
        record.setPayload(JSONUtil.toJsonStr(req.getPayload()));
        record.setComment(req.getComment());
        mapper.insert(record);

        eventBus.publish(new CountryEditSubmittedEvent(
            record.getTenantId(), record.getId(),
            Long.valueOf(req.getBizId()), req.getApplicantId()
        ));
        return record;
    }

    @Transactional
    public void approve(Long approvalId, Long approverId, String comment) {
        ApprovalRecord record = mapper.selectById(approvalId);
        if (record == null || !Objects.equals(record.getStatus(), ApprovalStatus.PENDING.name())) {
            throw ApprovalException.notPending();
        }
        if (!hasRole(approverId, record.getApprovalRole())) {
            throw ApprovalException.noPermission();
        }

        // CAS update：WHERE status = 'PENDING' 防止两个 SUPER_ADMIN 同时 approve
        // 同一 record 导致重复事件。MP OptimisticLockerInnerInterceptor 在
        // 3.5.7 上加了 WHERE version=? 但**不会**在 rowsAffected=0 时抛错——
        // 所以单靠 version 字段不能挡住并发。CAS 是这里的真正护栏。
        ApprovalRecord upd = new ApprovalRecord();
        upd.setId(record.getId());
        upd.setStatus(ApprovalStatus.APPROVED.name());
        upd.setApproverId(approverId);
        upd.setComment(comment);
        upd.setDecidedAt(LocalDateTime.now());
        int rows = mapper.update(upd,
            new LambdaUpdateWrapper<ApprovalRecord>()
                .eq(ApprovalRecord::getId, approvalId)
                .eq(ApprovalRecord::getStatus, ApprovalStatus.PENDING.name()));
        if (rows == 0) {
            // 另一个线程在我们 SELECT 之后 UPDATE 把 status 改成 APPROVED；
            // 抛 notPending 让当前事务回滚，eventBus.publish 的 outbox row 一起回滚。
            throw ApprovalException.notPending();
        }

        eventBus.publish(new CountryEditApprovedEvent(
            record.getTenantId(), record.getId(),
            Long.valueOf(record.getBizId()),
            approverId, record.getApplicantId(), record.getPayload()
        ));
    }

    @Transactional
    public void reject(Long approvalId, Long approverId, String reason) {
        ApprovalRecord record = mapper.selectById(approvalId);
        if (record == null || !Objects.equals(record.getStatus(), ApprovalStatus.PENDING.name())) {
            throw ApprovalException.notPending();
        }
        if (!hasRole(approverId, record.getApprovalRole())) {
            throw ApprovalException.noPermission();
        }

        // CAS update 同 approve()
        ApprovalRecord upd = new ApprovalRecord();
        upd.setId(record.getId());
        upd.setStatus(ApprovalStatus.REJECTED.name());
        upd.setApproverId(approverId);
        upd.setComment(reason);
        upd.setDecidedAt(LocalDateTime.now());
        int rows = mapper.update(upd,
            new LambdaUpdateWrapper<ApprovalRecord>()
                .eq(ApprovalRecord::getId, approvalId)
                .eq(ApprovalRecord::getStatus, ApprovalStatus.PENDING.name()));
        if (rows == 0) {
            throw ApprovalException.notPending();
        }

        eventBus.publish(new CountryEditRejectedEvent(
            record.getTenantId(), record.getId(),
            Long.valueOf(record.getBizId()),
            approverId, reason
        ));
    }

    @Transactional
    public void withdraw(Long approvalId, Long applicantId) {
        ApprovalRecord record = mapper.selectById(approvalId);
        if (record == null) throw ApprovalException.notPending();
        if (!Objects.equals(record.getApplicantId(), applicantId)) {
            throw ApprovalException.notApplicant();
        }
        if (!Objects.equals(record.getStatus(), ApprovalStatus.PENDING.name())) {
            throw ApprovalException.notPending();
        }

        // CAS update 同 approve()
        ApprovalRecord upd = new ApprovalRecord();
        upd.setId(record.getId());
        upd.setStatus(ApprovalStatus.WITHDRAWN.name());
        upd.setDecidedAt(LocalDateTime.now());
        int rows = mapper.update(upd,
            new LambdaUpdateWrapper<ApprovalRecord>()
                .eq(ApprovalRecord::getId, approvalId)
                .eq(ApprovalRecord::getStatus, ApprovalStatus.PENDING.name()));
        if (rows == 0) {
            throw ApprovalException.notPending();
        }
    }

    private boolean hasRole(Long userId, String roleCode) {
        return permissionService.userHasRole(TenantContext.get(), userId, roleCode);
    }
}
