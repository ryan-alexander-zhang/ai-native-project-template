package com.aipersimmon.ddd.outbox.mybatisplus;

import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.DeadLetters;
import com.aipersimmon.ddd.outbox.engine.store.OutboxStore;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import java.time.Clock;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contributes the MyBatis-Plus-backed storage adapters once MyBatis-Plus has produced a {@code
 * SqlSessionFactory}: the {@link OutboxStore} the engine runs on, the dead-letter store, and the
 * dead-letter read side. It registers only its own mappers (as {@code MapperFactoryBean}s), so it
 * never triggers or hijacks the consumer's {@code @MapperScan}.
 *
 * <p>That is deliberately all it does. The writer, relay, schedule and cleanup are assembled by
 * {@code AipersimmonDddOutboxEngineAutoConfiguration} over the store port, so this backend and the
 * JdbcTemplate one cannot drift in how they order, retry or give up on a message. What is left here
 * is what only this backend can answer: the mapper access, and the ShedLock lease — a lock table in
 * the same database, hence not the engine's business.
 */
@AutoConfiguration(
    after = {
      MybatisPlusAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class
    })
public class AipersimmonDddOutboxMybatisPlusAutoConfiguration {

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
  @ConditionalOnMissingBean(OutboxStore.class)
  public OutboxStore outboxStore(OutboxMapper outboxMapper) {
    return new MybatisOutboxStore(outboxMapper);
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

  /**
   * The read side of the dead-letter table, so an operations surface can find what to replay. A
   * separate bean from the store because the two are separate ports: an application that replaces
   * {@link DeadLetterStore} with a forwarder keeps this reader only if its rows are still here.
   */
  @Bean
  @ConditionalOnBean(SqlSessionFactory.class)
  @ConditionalOnMissingBean(DeadLetters.class)
  public DeadLetters outboxDeadLetters(DeadLetterMapper deadLetterMapper) {
    return new MybatisDeadLetters(deadLetterMapper);
  }

  /**
   * Enables ShedLock and provides its {@link LockProvider} whenever a {@link DataSource} is
   * present, so the retention purge runs on one instance at a time rather than having every
   * instance delete the same rows. The relay does not use it: delivery is guarded per row by the
   * lease it claims, which is what lets every instance poll and lets a lost instance cost only its
   * own claimed rows. The lock table ({@code shedlock}) must exist (see the reference DDL); the
   * provider uses the database clock ({@code usingDbTime}) so the lock does not depend on the
   * instances' wall clocks being in sync. An application can override the {@code LockProvider} bean
   * (for example a Redis-backed one) to lock elsewhere.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnBean(DataSource.class)
  @EnableSchedulerLock(
      defaultLockAtMostFor = "${aipersimmon.ddd.outbox.cleanup.lock-at-most-for:PT10M}")
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
