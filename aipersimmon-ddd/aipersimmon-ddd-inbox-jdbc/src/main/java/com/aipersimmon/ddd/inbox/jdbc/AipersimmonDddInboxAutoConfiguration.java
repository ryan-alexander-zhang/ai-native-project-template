package com.aipersimmon.ddd.inbox.jdbc;

import com.aipersimmon.ddd.application.Inbox;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires a JDBC-backed {@link Inbox} once a {@code JdbcTemplate} is available. An
 * application can override it by defining its own {@code Inbox} bean.
 */
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
public class AipersimmonDddInboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock inboxClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean(Inbox.class)
    public Inbox jdbcInbox(JdbcTemplate jdbcTemplate, Clock inboxClock) {
        return new JdbcInbox(jdbcTemplate, inboxClock);
    }
}
