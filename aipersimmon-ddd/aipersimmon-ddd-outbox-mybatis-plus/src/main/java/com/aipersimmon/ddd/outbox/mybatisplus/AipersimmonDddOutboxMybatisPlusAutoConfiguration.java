package com.aipersimmon.ddd.outbox.mybatisplus;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.observability.NoOpStoreAndForwardTracer;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
import com.aipersimmon.ddd.outbox.AipersimmonDddOutboxAutoConfiguration;
import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.FailureClassifier;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxProperties;
import com.aipersimmon.ddd.outbox.RetryBackoff;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the MyBatis-Plus-backed outbox storage once MyBatis-Plus has produced a {@code
 * SqlSessionFactory}: a writer that implements the integration-event publisher port and a scheduled
 * relay that polls unsent rows and hands them to the {@link OutboxDispatcher} chosen by the
 * storage-agnostic {@link AipersimmonDddOutboxAutoConfiguration} (ordered before this class). It
 * registers only its own {@link OutboxMapper} (a {@code MapperFactoryBean}), so it never triggers
 * or hijacks the consumer's {@code @MapperScan}. Enables scheduling so the relay runs in the
 * background; an application can override any of these beans.
 */
@AutoConfiguration(
    after = {
      MybatisPlusAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class,
      AipersimmonDddOutboxAutoConfiguration.class
    },
    // Register before the in-process events fallback so this durable writer claims the
    // IntegrationEvents port and the fallback backs off. String form: this module
    // does not depend on events-spring, and an absent target is simply ignored.
    beforeName = "com.aipersimmon.ddd.events.spring.AipersimmonDddEventsAutoConfiguration")
@EnableScheduling
public class AipersimmonDddOutboxMybatisPlusAutoConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(AipersimmonDddOutboxMybatisPlusAutoConfiguration.class);

  // Name-scoped so this component always contributes its own named clock and injects it by name,
  // rather than backing off when another component already registered a Clock of the same type.
  @Bean
  @ConditionalOnMissingBean(name = "outboxClock")
  public Clock outboxClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnBean(SqlSessionFactory.class)
  @ConditionalOnMissingBean
  public MapperFactoryBean<OutboxMapper> aipersimmonOutboxMapper(
      SqlSessionFactory sqlSessionFactory) {
    MapperFactoryBean<OutboxMapper> factory = new MapperFactoryBean<>(OutboxMapper.class);
    factory.setSqlSessionFactory(sqlSessionFactory);
    return factory;
  }

  @Bean
  @ConditionalOnBean(SqlSessionFactory.class)
  @ConditionalOnMissingBean
  public MapperFactoryBean<DeadLetterMapper> aipersimmonDeadLetterMapper(
      SqlSessionFactory sqlSessionFactory) {
    MapperFactoryBean<DeadLetterMapper> factory = new MapperFactoryBean<>(DeadLetterMapper.class);
    factory.setSqlSessionFactory(sqlSessionFactory);
    return factory;
  }

  @Bean
  @ConditionalOnBean(SqlSessionFactory.class)
  @ConditionalOnMissingBean(DeadLetterStore.class)
  public DeadLetterStore outboxDeadLetterStore(
      OutboxMapper outboxMapper,
      DeadLetterMapper deadLetterMapper,
      PlatformTransactionManager transactionManager,
      Clock outboxClock) {
    return new MybatisDeadLetterStore(
        outboxMapper, deadLetterMapper, new TransactionTemplate(transactionManager), outboxClock);
  }

  @Bean
  @ConditionalOnBean(SqlSessionFactory.class)
  @ConditionalOnMissingBean(IntegrationEvents.class)
  public IntegrationEvents outboxWriter(
      OutboxMapper outboxMapper,
      ObjectProvider<ObjectMapper> objectMapper,
      Clock outboxClock,
      @Value("${aipersimmon.ddd.integration.source:${spring.application.name:aipersimmon}}")
          String source,
      ObjectProvider<StoreAndForwardTracer> tracer) {
    log.info(
        "aipersimmon-ddd integration-event transport: durable transactional outbox (MyBatis-Plus)");
    return new OutboxWriter(
        outboxMapper,
        objectMapper.getIfAvailable(ObjectMapper::new),
        outboxClock,
        source,
        tracer.getIfAvailable(() -> NoOpStoreAndForwardTracer.INSTANCE));
  }

  @Bean
  @ConditionalOnBean(SqlSessionFactory.class)
  @ConditionalOnMissingBean
  public OutboxRelay outboxRelay(
      OutboxMapper outboxMapper,
      OutboxDispatcher outboxDispatcher,
      DeadLetterStore deadLetterStore,
      FailureClassifier failureClassifier,
      Clock outboxClock,
      OutboxProperties properties,
      ObjectProvider<StoreAndForwardTracer> tracer) {
    return new OutboxRelay(
        outboxMapper,
        outboxDispatcher,
        deadLetterStore,
        failureClassifier,
        new RetryBackoff(
            properties.getRetry().getBaseBackoffMs(), properties.getRetry().getMaxBackoffMs()),
        outboxClock,
        properties.getBatchSize(),
        properties.getMaxAttempts(),
        tracer.getIfAvailable(() -> NoOpStoreAndForwardTracer.INSTANCE));
  }

  @Bean
  @ConditionalOnBean(SqlSessionFactory.class)
  @ConditionalOnProperty(name = "aipersimmon.ddd.outbox.cleanup.enabled", havingValue = "true")
  @ConditionalOnMissingBean
  public OutboxCleanup outboxCleanup(
      OutboxMapper outboxMapper, Clock outboxClock, OutboxProperties properties) {
    return new OutboxCleanup(
        outboxMapper, outboxClock, properties.getCleanup().getRetentionSeconds());
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
      defaultLockAtMostFor = "${aipersimmon.ddd.outbox.relay.lock-at-most-for:PT60M}")
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
