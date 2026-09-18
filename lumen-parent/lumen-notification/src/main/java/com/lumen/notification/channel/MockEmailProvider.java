package com.lumen.notification.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MockEmailProvider implements ChannelProvider {

    @Override
    public String channel() {
        return "EMAIL";
    }

    @Override
    public SendResult send(SendRequest req) {
        log.info("[MOCK-EMAIL] to={} subject={} content={}", req.receiver(), req.subject(), req.content());
        return new SendResult(true, "mock-email-" + System.currentTimeMillis(), null);
    }
}
