package com.aipersimmon.ddd.outbox.jdbc;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.observability.NoOpStoreAndForwardTracer;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
import com.aipersimmon.ddd.outbox.AipersimmonDddOutboxAutoConfiguration;
import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.FailureClassifier;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.RetryBackoff;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the JdbcTemplate-backed outbox storage once a {@code JdbcTemplate} is available: a writer
 * that implements the integration-event publisher port and a scheduled relay that polls unsent rows
 * and hands them to the {@link OutboxDispatcher} chosen by the storage-agnostic {@link
 * AipersimmonDddOutboxAutoConfiguration} (ordered before this class). Enables scheduling so the
 * relay runs in the background; an application can override any of these beans.
 */
@AutoConfiguration(
    after = {
      JdbcTemplateAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class,
      AipersimmonDddOutboxAutoConfiguration.class
    },
    // Register before the in-process events fallback so this durable writer claims the
    // IntegrationEvents port and the fallback backs off (issue-00044). String form: this module
    // does not depend on events-spring, and an absent target is simply ignored.
    beforeName = "com.aipersimmon.ddd.events.spring.AipersimmonDddEventsAutoConfiguration")
@EnableScheduling
public class AipersimmonDddOutboxJdbcAutoConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(AipersimmonDddOutboxJdbcAutoConfiguration.class);

  // Name-scoped so this component always contributes its own named clock and injects it by name,
  // rather than backing off when another component (process-manager, inbox) already registered a
  // Clock of the same type — which would leave the by-name `outboxClock` injections unresolved. See
  // issue-00026.
  @Bean
  @ConditionalOnMissingBean(name = "outboxClock")
  public Clock outboxClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnBean(JdbcTemplate.class)
  @ConditionalOnMissingBean(IntegrationEvents.class)
  public IntegrationEvents outboxWriter(
      JdbcTemplate jdbcTemplate,
      ObjectProvider<ObjectMapper> objectMapper,
      Clock outboxClock,
      @Value("${aipersimmon.ddd.integration.source:${spring.application.name:aipersimmon}}")
          String source,
      ObjectProvider<StoreAndForwardTracer> tracer) {
    log.info(
        "aipersimmon-ddd integration-event transport: durable transactional outbox (JdbcTemplate)");
    return new OutboxWriter(
        jdbcTemplate,
        objectMapper.getIfAvailable(ObjectMapper::new),
        outboxClock,
        source,
        tracer.getIfAvailable(() -> NoOpStoreAndForwardTracer.INSTANCE));
  }

  @Bean
  @ConditionalOnBean(JdbcTemplate.class)
  @ConditionalOnMissingBean(DeadLetterStore.class)
  public DeadLetterStore outboxDeadLetterStore(
      JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager, Clock outboxClock) {
    return new JdbcDeadLetterStore(
        jdbcTemplate, new TransactionTemplate(transactionManager), outboxClock);
  }

  @Bean
  @ConditionalOnBean(JdbcTemplate.class)
  @ConditionalOnMissingBean
  public OutboxRelay outboxRelay(
      JdbcTemplate jdbcTemplate,
      OutboxDispatcher outboxDispatcher,
      DeadLetterStore deadLetterStore,
      FailureClassifier failureClassifier,
      Clock outboxClock,
      @Value("${aipersimmon.ddd.outbox.batch-size:100}") int batchSize,
      @Value("${aipersimmon.ddd.outbox.max-attempts:10}") int maxAttempts,
      @Value("${aipersimmon.ddd.outbox.retry.base-backoff-ms:1000}") long baseBackoffMs,
      @Value("${aipersimmon.ddd.outbox.retry.max-backoff-ms:60000}") long maxBackoffMs,
      ObjectProvider<StoreAndForwardTracer> tracer) {
    return new OutboxRelay(
        jdbcTemplate,
        outboxDispatcher,
        deadLetterStore,
        failureClassifier,
        new RetryBackoff(baseBackoffMs, maxBackoffMs),
        outboxClock,
        batchSize,
        maxAttempts,
        tracer.getIfAvailable(() -> NoOpStoreAndForwardTracer.INSTANCE));
  }

  @Bean
  @ConditionalOnBean(JdbcTemplate.class)
  @ConditionalOnProperty(name = "aipersimmon.ddd.outbox.cleanup.enabled", havingValue = "true")
  @ConditionalOnMissingBean
  public OutboxCleanup outboxCleanup(
      JdbcTemplate jdbcTemplate,
      Clock outboxClock,
      @Value("${aipersimmon.ddd.outbox.cleanup.retention-seconds:604800}") long retentionSeconds) {
    return new OutboxCleanup(jdbcTemplate, outboxClock, retentionSeconds);
  }

  /**
   * Enables ShedLock and provides its {@link LockProvider} whenever a {@link DataSource} is
   * present, so the scheduled {@link OutboxRelay} holds a database lock and runs on only one
   * instance at a time — a multi-instance deployment does not poll and dispatch the same rows once
   * per instance. The lock table ({@code shedlock}) must exist (see the reference DDL); the
   * provider uses the database clock ({@code usingDbTime}) so the lock does not depend on the
   * instances' wall clocks being in sync. An application can override the {@code LockProvider} bean
   * (for example a Redis-backed one) to lock elsewhere.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnBean(DataSource.class)
  @EnableSchedulerLock(
      defaultLockAtMostFor = "${aipersimmon.ddd.outbox.relay.lock-at-most-for:PT10M}")
  static class OutboxSchedulerLockConfiguration {

    @Bean
    @ConditionalOnMissingBean(LockProvider.class)
    public LockProvider outboxLockProvider(DataSource dataSource) {
      return new JdbcTemplateLockProvider(
          JdbcTemplateLockProvider.Configuration.builder()
              .withJdbcTemplate(new JdbcTemplate(dataSource))
              .usingDbTime()
              .build());
    }
  }
}
