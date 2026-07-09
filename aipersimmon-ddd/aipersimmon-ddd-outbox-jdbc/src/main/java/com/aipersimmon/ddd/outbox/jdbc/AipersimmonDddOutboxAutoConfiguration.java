package com.aipersimmon.ddd.outbox.jdbc;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the outbox once a {@code JdbcTemplate} is available: a writer that
 * implements the integration-event publisher port, a scheduled relay, and a
 * dispatcher. The default dispatcher only logs; setting
 * {@code aipersimmon.ddd.outbox.dispatch=in-process} switches to republishing
 * events in process instead. Enables scheduling so the relay runs in the
 * background; an application can override any of these beans.
 */
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
@EnableScheduling
public class AipersimmonDddOutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock outboxClock() {
        return Clock.systemUTC();
    }

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

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean(IntegrationEvents.class)
    public IntegrationEvents outboxWriter(JdbcTemplate jdbcTemplate, Clock outboxClock) {
        return new OutboxWriter(jdbcTemplate, new ObjectMapper(), outboxClock);
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean
    public OutboxRelay outboxRelay(JdbcTemplate jdbcTemplate, OutboxDispatcher outboxDispatcher,
                                   Clock outboxClock,
                                   @Value("${aipersimmon.ddd.outbox.batch-size:100}") int batchSize) {
        return new OutboxRelay(jdbcTemplate, outboxDispatcher, outboxClock, batchSize);
    }
}
