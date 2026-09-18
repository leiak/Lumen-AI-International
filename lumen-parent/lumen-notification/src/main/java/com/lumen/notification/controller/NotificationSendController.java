package com.lumen.notification.controller;

import com.lumen.common.api.R;
import com.lumen.common.audit.Audit;
import com.lumen.notification.dispatcher.NotificationDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/notification/send")
@RequiredArgsConstructor
public class NotificationSendController {

    private final NotificationDispatcher dispatcher;

    @PostMapping
    @PreAuthorize("hasAuthority('notification_template:send')")
    @Audit(action = "send", resource = "notification", recordResponse = false)
    @SuppressWarnings("unchecked")
    public R<Void> send(@RequestBody Map<String, Object> body) {
        String templateCode = (String) body.get("templateCode");
        String receiver = (String) body.get("receiver");
        Map<String, Object> vars = (Map<String, Object>) body.get("vars");
        return dispatcher.send(templateCode, receiver, vars);
    }
}
