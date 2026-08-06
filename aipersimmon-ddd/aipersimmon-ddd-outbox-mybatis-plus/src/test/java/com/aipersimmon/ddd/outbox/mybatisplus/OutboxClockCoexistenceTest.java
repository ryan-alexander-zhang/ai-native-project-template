package com.aipersimmon.ddd.outbox.mybatisplus;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.id.AipersimmonDddIdAutoConfiguration;
import com.aipersimmon.ddd.outbox.engine.autoconfigure.AipersimmonDddOutboxEngineAutoConfiguration;
import com.aipersimmon.ddd.outbox.spring.AipersimmonDddOutboxAutoConfiguration;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Regression test: when another component (here a stand-in {@code processManagerClock}) already
 * contributes a {@code Clock} bean, this starter must still contribute its own {@code outboxClock}
 * and wire {@code outboxWriter} to it — not back off and leave the by-name injection dangling.
 *
 * <p>Guards both root causes at once: the name-scoped {@code @ConditionalOnMissingBean(name =
 * "outboxClock")} keeps {@code outboxClock} present despite the foreign clock, and {@code
 * -parameters} (compiler flag on the library parent) lets {@code outboxWriter}'s {@code Clock
 * outboxClock} parameter resolve by name across the two candidates. Before the fix, {@code
 * outboxClock} backed off (type-scoped condition) so this context could not wire {@code
 * outboxWriter} deterministically.
 */
class OutboxClockCoexistenceTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  DataSourceAutoConfiguration.class,
                  DataSourceTransactionManagerAutoConfiguration.class,
                  JdbcTemplateAutoConfiguration.class,
                  // The MyBatis-Plus store hangs off a SqlSessionFactory, so MyBatis-Plus's own
                  // auto-configuration is part of the minimal assembly on this backend.
                  MybatisPlusAutoConfiguration.class,
                  AipersimmonDddOutboxAutoConfiguration.class,
                  AipersimmonDddOutboxMybatisPlusAutoConfiguration.class,
                  // The clock and the writer live in the engine now; this backend contributes the
                  // store they run on, so the minimal assembly is both.
                  AipersimmonDddOutboxEngineAutoConfiguration.class,
                  // The outbox writer requires an IdGenerator (issue-00053), so the module that
                  // supplies it is part of the minimal assembly.
                  AipersimmonDddIdAutoConfiguration.class))
          // This runner never applies the outbox schema (no spring.sql.init here), and the clock
          // wiring under test does not touch a table — so the startup schema probe, which would
          // correctly fail an empty database, is switched off rather than satisfied.
          .withPropertyValues("aipersimmon.ddd.outbox.schema-validation=none")
          .withUserConfiguration(ForeignClockConfig.class);

  @Test
  void keepsItsOwnNamedClockAlongsideAnotherComponentsClock() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasBean("outboxClock");
          assertThat(context).hasBean("processManagerClock");
          // outboxWriter (the IntegrationEvents port) wired successfully — its Clock resolved by
          // name.
          assertThat(context).hasSingleBean(IntegrationEvents.class);
        });
  }

  /** Stands in for another starter (e.g. process-manager) contributing a second Clock bean. */
  @Configuration(proxyBeanMethods = false)
  static class ForeignClockConfig {
    @Bean
    Clock processManagerClock() {
      return Clock.systemUTC();
    }
  }
}
