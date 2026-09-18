package com.lumen.notification.service;

import com.lumen.common.tenant.TenantContext;
import com.lumen.notification.entity.NotificationTemplate;
import com.lumen.notification.mapper.NotificationTemplateMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationTemplateServiceTest {

    @Mock
    private NotificationTemplateMapper templateMapper;

    @InjectMocks
    private NotificationTemplateServiceImpl svc;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void create_populatesTenantIdFromContext() {
        TenantContext.set(99L);

        NotificationTemplate tpl = new NotificationTemplate();
        tpl.setCode("T1");
        tpl.setChannel("EMAIL");
        tpl.setSubject("Test");
        tpl.setContent("Hello ${name}");
        tpl.setVars("[\"name\"]");
        tpl.setStatus(1);

        when(templateMapper.insert(any(NotificationTemplate.class))).thenReturn(1);

        NotificationTemplate saved = svc.create(tpl);

        ArgumentCaptor<NotificationTemplate> captor = ArgumentCaptor.forClass(NotificationTemplate.class);
        verify(templateMapper).insert(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(99L);
        assertThat(saved.getCode()).isEqualTo("T1");
    }

    @Test
    void template_canCarryRenderableContent() {
        NotificationTemplate tpl = new NotificationTemplate();
        tpl.setCode("T2");
        tpl.setChannel("SMS");
        tpl.setContent("Hello ${name}, your code is ${code}");

        assertThat(tpl.getContent()).contains("${name}").contains("${code}");
    }
}
