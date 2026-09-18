package com.lumen.notification.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MockImProvider implements ChannelProvider {

    @Override
    public String channel() {
        return "IM";
    }

    @Override
    public SendResult send(SendRequest req) {
        log.info("[MOCK-IM] to={} content={}", req.receiver(), req.content());
        return new SendResult(true, "mock-im-" + System.currentTimeMillis(), null);
    }
}
