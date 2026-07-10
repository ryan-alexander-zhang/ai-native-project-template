package com.aipersimmon.ddd.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * Selects the outbox {@link OutboxDispatcher}, independently of how the outbox is
 * stored. The default only logs; setting
 * {@code aipersimmon.ddd.outbox.dispatch=in-process} switches to republishing
 * events in process instead. A messaging starter (for example
 * {@code -messaging-kafka}) can order itself before this class to register a
 * broker-backed dispatcher that wins over the logging default, and it works with
 * any storage backend because this dispatch wiring carries no persistence.
 *
 * <p>The storage starter ({@code aipersimmon-ddd-outbox-jdbc},
 * {@code -outbox-mybatis-plus}, ...) orders itself after this class so the chosen
 * dispatcher bean exists when its relay is built.
 */
@AutoConfiguration
public class AipersimmonDddOutboxAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "aipersimmon.ddd.outbox.dispatch", havingValue = "in-process")
    public OutboxDispatcher inProcessOutboxDispatcher(ApplicationEventPublisher publisher) {
        return new InProcessOutboxDispatcher(publisher, new ObjectMapper());
    }

    @Bean
    @ConditionalOnMissingBean(OutboxDispatcher.class)
    public OutboxDispatcher loggingOutboxDispatcher() {
        return new LoggingOutboxDispatcher();
    }
}
