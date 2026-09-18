package com.lumen.notification.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MockSmsProvider implements ChannelProvider {

    @Override
    public String channel() {
        return "SMS";
    }

    @Override
    public SendResult send(SendRequest req) {
        log.info("[MOCK-SMS] to={} content={}", req.receiver(), req.content());
        return new SendResult(true, "mock-sms-" + System.currentTimeMillis(), null);
    }
}
