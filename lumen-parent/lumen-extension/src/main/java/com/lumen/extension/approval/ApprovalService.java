package com.lumen.extension.approval;

import cn.hutool.json.JSONUtil;
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

        record.setStatus(ApprovalStatus.APPROVED.name());
        record.setApproverId(approverId);
        record.setComment(comment);
        record.setDecidedAt(LocalDateTime.now());
        mapper.updateById(record);

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

        record.setStatus(ApprovalStatus.REJECTED.name());
        record.setApproverId(approverId);
        record.setComment(reason);
        record.setDecidedAt(LocalDateTime.now());
        mapper.updateById(record);

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
        record.setStatus(ApprovalStatus.WITHDRAWN.name());
        record.setDecidedAt(LocalDateTime.now());
        mapper.updateById(record);
    }

    private boolean hasRole(Long userId, String roleCode) {
        return permissionService.userHasRole(TenantContext.get(), userId, roleCode);
    }
}
