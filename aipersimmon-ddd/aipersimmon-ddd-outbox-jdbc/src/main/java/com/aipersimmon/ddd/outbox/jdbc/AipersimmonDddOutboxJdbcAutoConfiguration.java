package com.aipersimmon.ddd.outbox.jdbc;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.outbox.AipersimmonDddOutboxAutoConfiguration;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the JdbcTemplate-backed outbox storage once a {@code JdbcTemplate} is
 * available: a writer that implements the integration-event publisher port and a
 * scheduled relay that polls unsent rows and hands them to the
 * {@link OutboxDispatcher} chosen by the storage-agnostic
 * {@link AipersimmonDddOutboxAutoConfiguration} (ordered before this class).
 * Enables scheduling so the relay runs in the background; an application can
 * override any of these beans.
 */
@AutoConfiguration(after = {
        JdbcTemplateAutoConfiguration.class,
        AipersimmonDddOutboxAutoConfiguration.class})
@EnableScheduling
public class AipersimmonDddOutboxJdbcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock outboxClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean(IntegrationEvents.class)
    public IntegrationEvents outboxWriter(JdbcTemplate jdbcTemplate, Clock outboxClock,
            @Value("${aipersimmon.ddd.integration.source:${spring.application.name:aipersimmon}}") String source) {
        return new OutboxWriter(jdbcTemplate, new ObjectMapper(), outboxClock, source);
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
