package com.lumen.notification.service;

import com.lumen.common.api.PageResult;
import com.lumen.notification.entity.NotificationTemplate;

public interface NotificationTemplateService {
    PageResult<NotificationTemplate> page(long pageNum, long pageSize, String keyword);
    NotificationTemplate create(NotificationTemplate entity);
    NotificationTemplate update(Long id, NotificationTemplate entity);
    NotificationTemplate getById(Long id);
    void delete(Long id);
}
