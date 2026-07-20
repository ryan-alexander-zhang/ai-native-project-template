package com.aipersimmon.ddd.inbox.jdbc;

import com.aipersimmon.ddd.application.Inbox;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires a JDBC-backed {@link Inbox} once a {@code JdbcTemplate} is available. An
 * application can override it by defining its own {@code Inbox} bean. Retention
 * cleanup is opt-in via {@code aipersimmon.ddd.inbox.cleanup.enabled=true}.
 */
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
public class AipersimmonDddInboxAutoConfiguration {

    // Name-scoped so this component always contributes its own named clock and injects it by name,
    // rather than backing off when another component already registered a Clock of the same type. See
    // issue-00026.
    @Bean
    @ConditionalOnMissingBean(name = "inboxClock")
    public Clock inboxClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean(Inbox.class)
    public Inbox jdbcInbox(JdbcTemplate jdbcTemplate, Clock inboxClock,
            @Value("${aipersimmon.ddd.inbox.consumer:${spring.application.name:aipersimmon}}") String consumer) {
        return new JdbcInbox(jdbcTemplate, inboxClock, consumer);
    }

    /**
     * Enables scheduling and wires the retention cleanup only when opted in, so the
     * common case adds no scheduled beans.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "aipersimmon.ddd.inbox.cleanup.enabled", havingValue = "true")
    @EnableScheduling
    static class InboxCleanupConfiguration {

        @Bean
        @ConditionalOnBean(JdbcTemplate.class)
        @ConditionalOnMissingBean
        public InboxCleanup inboxCleanup(JdbcTemplate jdbcTemplate, Clock inboxClock,
                @Value("${aipersimmon.ddd.inbox.cleanup.retention-seconds:2592000}") long retentionSeconds) {
            return new InboxCleanup(jdbcTemplate, inboxClock, retentionSeconds);
        }
    }
}
