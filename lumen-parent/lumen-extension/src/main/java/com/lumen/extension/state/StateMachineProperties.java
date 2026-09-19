package com.lumen.extension.state;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "lumen.state-machine")
public class StateMachineProperties {
    private Cache cache = new Cache();

    @Data
    public static class Cache {
        private long ttlSeconds = 30;
        private long maxSize = 1000;
    }
}