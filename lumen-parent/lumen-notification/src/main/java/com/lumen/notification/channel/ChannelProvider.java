package com.lumen.notification.channel;

import java.util.Map;

public interface ChannelProvider {

    String channel();

    SendResult send(SendRequest req);

    record SendRequest(String templateCode, String receiver, String subject, String content, Map<String, Object> vars) {}

    record SendResult(boolean ok, String messageId, String error) {}
}
