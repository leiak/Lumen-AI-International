package com.lumen.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.notification.entity.NotificationTemplate;
import com.lumen.notification.mapper.NotificationTemplateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationTemplateServiceImpl implements NotificationTemplateService {

    private final NotificationTemplateMapper templateMapper;

    @Override
    public PageResult<NotificationTemplate> page(long pageNum, long pageSize, String keyword) {
        Page<NotificationTemplate> p = Page.of(pageNum, pageSize);
        QueryWrapper<NotificationTemplate> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.like("code", keyword).or().like("subject", keyword);
        }
        Page<NotificationTemplate> res = templateMapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), pageNum, pageSize);
    }

    @Override
    @Transactional
    public NotificationTemplate create(NotificationTemplate entity) {
        if (entity.getTenantId() == null) entity.setTenantId(TenantContext.require());
        templateMapper.insert(entity);
        return entity;
    }

    @Override
    public NotificationTemplate update(Long id, NotificationTemplate entity) {
        entity.setId(id);
        templateMapper.updateById(entity);
        return templateMapper.selectById(id);
    }

    @Override
    public NotificationTemplate getById(Long id) {
        return templateMapper.selectById(id);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        templateMapper.deleteById(id);
    }
}
