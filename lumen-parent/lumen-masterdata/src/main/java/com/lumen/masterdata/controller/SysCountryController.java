package com.lumen.masterdata.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.common.security.SecurityUtil;
import com.lumen.extension.approval.ApprovalRecord;
import com.lumen.extension.approval.ApprovalService;
import com.lumen.extension.approval.SubmitApprovalRequest;
import com.lumen.masterdata.entity.SysCountry;
import com.lumen.masterdata.service.SysCountryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/countries")
@RequiredArgsConstructor
public class SysCountryController {
    private final SysCountryService countryService;
    private final ApprovalService approvalService;

    @GetMapping
    @PreAuthorize("hasAuthority('country:list')")
    public R<PageResult<SysCountry>> page(@RequestParam(defaultValue = "1") long pageNum,
                                          @RequestParam(defaultValue = "20") long pageSize,
                                          @RequestParam(required = false) String keyword) {
        return R.ok(countryService.page(pageNum, pageSize, keyword));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('country:create')")
    @Audit(action = "create", resource = "country")
    public R<SysCountry> create(@RequestBody SysCountry entity) {
        return R.ok(countryService.create(entity));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('country:view')")
    public R<SysCountry> get(@PathVariable Long id) { return R.ok(countryService.getById(id)); }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('country:update')")
    @Audit(action = "update", resource = "country", recordResponse = false)
    public R<SysCountry> update(@PathVariable Long id, @RequestBody SysCountry entity) {
        return R.ok(countryService.update(id, entity));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('country:delete')")
    @Audit(action = "delete", resource = "country")
    public R<Void> delete(@PathVariable Long id) { countryService.delete(id); return R.ok(null); }

    /**
     * 提交编辑申请：把客户端想改的字段打包成 payload 走审批流，审批通过后由
     * {@code CountryEditApprovedListener} 异步应用变更到 sys_country。
     *
     * <p>当前用户从 {@link SecurityUtil#getCurrentUserId()} 取，
     * 无登录态时申请人 ID 为 null（{@code ApprovalService.submit} 会照常落库，
     * 但 {@code withdraw} 要求申请人 ID 匹配时需要登录态才能撤回）。
     */
    @PostMapping("/{id}/edit-submit")
    @PreAuthorize("hasAuthority('country:update')")
    @Audit(action = "submit_edit", resource = "country")
    public R<Long> submitEdit(@PathVariable Long id, @RequestBody Map<String, Object> changes) {
        Long applicantId = SecurityUtil.getCurrentUserId();
        SubmitApprovalRequest req = new SubmitApprovalRequest(
                "country_edit", String.valueOf(id), applicantId, changes, null
        );
        ApprovalRecord record = approvalService.submit(req);
        return R.ok(record.getId());
    }
}
