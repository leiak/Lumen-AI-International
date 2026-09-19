package com.lumen.extension.approval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.common.security.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/approvals")
@RequiredArgsConstructor
public class ApprovalController {
    private final ApprovalService approvalService;
    private final ApprovalRecordMapper mapper;

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('approval:approve')")
    @Audit(action = "approve", resource = "approval")
    public R<Void> approve(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        String comment = body != null ? body.get("comment") : null;
        approvalService.approve(id, SecurityUtil.getCurrentUserId(), comment);
        return R.ok(null);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('approval:approve')")
    @Audit(action = "reject", resource = "approval")
    public R<Void> reject(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;
        approvalService.reject(id, SecurityUtil.getCurrentUserId(), reason);
        return R.ok(null);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('approval:list')")
    public R<PageResult<ApprovalRecord>> list(@RequestParam(defaultValue = "1") long pageNum,
                                               @RequestParam(defaultValue = "20") long pageSize,
                                               @RequestParam(required = false) String status) {
        Page<ApprovalRecord> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<ApprovalRecord> q = new LambdaQueryWrapper<>();
        if (status != null) q.eq(ApprovalRecord::getStatus, status);
        q.orderByDesc(ApprovalRecord::getId);
        Page<ApprovalRecord> result = mapper.selectPage(page, q);
        return R.ok(PageResult.of(result.getRecords(), result.getTotal(), pageNum, pageSize));
    }
}
