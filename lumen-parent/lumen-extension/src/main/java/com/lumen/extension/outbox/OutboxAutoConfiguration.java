package com.lumen.extension.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Auto-configures the outbox subsystem. Note: {@code @EnableScheduling} activates
 * the global TaskScheduler — currently only the {@code OutboxDispatcher} uses it,
 * but any future {@code @Scheduled} method in any module will also run.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(EventBus.class)
    public EventBus defaultEventBus(EventOutboxMapper mapper,
                                    org.springframework.context.ApplicationEventPublisher publisher) {
        return new DefaultEventBus(mapper, publisher);
    }
}