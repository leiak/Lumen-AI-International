package com.lumen.notification.controller;

import com.lumen.common.api.PageResult;
import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.notification.entity.NotificationTemplate;
import com.lumen.notification.service.NotificationTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notification/templates")
@RequiredArgsConstructor
public class NotificationTemplateController {

    private final NotificationTemplateService templateService;

    @GetMapping
    @PreAuthorize("hasAuthority('notification_template:list')")
    public R<PageResult<NotificationTemplate>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                     @RequestParam(defaultValue = "20") long pageSize,
                                                     @RequestParam(required = false) String keyword) {
        return R.ok(templateService.page(pageNum, pageSize, keyword));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('notification_template:create')")
    @Audit(action = "create", resource = "notification_template")
    public R<NotificationTemplate> create(@RequestBody NotificationTemplate entity) {
        return R.ok(templateService.create(entity));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('notification_template:view')")
    public R<NotificationTemplate> get(@PathVariable Long id) {
        return R.ok(templateService.getById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('notification_template:update')")
    @Audit(action = "update", resource = "notification_template", recordResponse = false)
    public R<NotificationTemplate> update(@PathVariable Long id, @RequestBody NotificationTemplate entity) {
        return R.ok(templateService.update(id, entity));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('notification_template:delete')")
    @Audit(action = "delete", resource = "notification_template")
    public R<Void> delete(@PathVariable Long id) {
        templateService.delete(id);
        return R.ok(null);
    }
}
