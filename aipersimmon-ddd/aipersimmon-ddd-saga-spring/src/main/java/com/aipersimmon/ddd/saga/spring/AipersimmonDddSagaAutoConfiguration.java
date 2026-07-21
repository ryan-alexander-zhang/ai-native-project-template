package com.aipersimmon.ddd.saga.spring;

import com.aipersimmon.ddd.saga.DeadlineHandler;
import com.aipersimmon.ddd.saga.DeadlineScheduler;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Auto-configures a {@link DeadlineScheduler} when the application supplies a {@link
 * DeadlineHandler} bean (its saga's timeout logic). Two implementations are selectable by {@code
 * aipersimmon.ddd.saga.deadline.store}:
 *
 * <ul>
 *   <li>{@code in-process} (default) — {@link SchedulingDeadlineScheduler}, backed by a {@link
 *       TaskScheduler}. Simple and broker-free, but pending deadlines live in memory and are lost
 *       on restart.
 *   <li>{@code jdbc} — {@link JdbcDeadlineScheduler}, which persists deadlines in a table and fires
 *       them from a scheduled poll, so they survive a restart and can be handled across instances.
 *       Requires a {@code JdbcTemplate} and the {@code aipersimmon_deadline} table.
 * </ul>
 *
 * <p>No {@code SagaStore} is auto-configured: a bounded context subclasses {@link JdbcSagaStore}
 * for its own saga and registers that as a bean.
 */
@AutoConfiguration
public class AipersimmonDddSagaAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(TaskScheduler.class)
  public TaskScheduler aipersimmonSagaTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("aipersimmon-saga-deadline-");
    scheduler.initialize();
    return scheduler;
  }

  @Bean
  @ConditionalOnBean(DeadlineHandler.class)
  @ConditionalOnMissingBean(DeadlineScheduler.class)
  @ConditionalOnProperty(
      name = "aipersimmon.ddd.saga.deadline.store",
      havingValue = "in-process",
      matchIfMissing = true)
  public DeadlineScheduler inProcessDeadlineScheduler(
      TaskScheduler taskScheduler, ObjectProvider<DeadlineHandler> handler) {
    // Resolve the handler lazily so a process manager can both arm deadlines
    // through this scheduler and be the handler, without a construction cycle.
    return new SchedulingDeadlineScheduler(taskScheduler, handler::getObject);
  }

  /**
   * Wires the durable JDBC scheduler when {@code aipersimmon.ddd.saga.deadline.store=jdbc} and a
   * {@code JdbcTemplate} is available. Enables scheduling so its poll runs.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(JdbcTemplate.class)
  @ConditionalOnProperty(name = "aipersimmon.ddd.saga.deadline.store", havingValue = "jdbc")
  @EnableScheduling
  static class JdbcDeadlineConfiguration {

    @Bean
    @ConditionalOnBean({DeadlineHandler.class, JdbcTemplate.class})
    @ConditionalOnMissingBean(DeadlineScheduler.class)
    public DeadlineScheduler jdbcDeadlineScheduler(
        JdbcTemplate jdbcTemplate,
        ObjectProvider<DeadlineHandler> handler,
        @Value("${aipersimmon.ddd.saga.deadline.batch-size:100}") int batchSize) {
      return new JdbcDeadlineScheduler(
          jdbcTemplate, handler::getObject, Clock.systemUTC(), batchSize);
    }
  }
}
