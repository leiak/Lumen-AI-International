package com.lumen.extension.outbox;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "lumen.outbox")
public class OutboxProperties {
    private boolean enabled = true;
    private int batchSize = 50;
    private long pollIntervalMs = 2000;
}