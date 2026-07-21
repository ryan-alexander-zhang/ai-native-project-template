package com.aipersimmon.ddd.outbox.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.outbox.AipersimmonDddOutboxAutoConfiguration;
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
 * Regression for issue-00026: when another component (here a stand-in {@code processManagerClock})
 * already contributes a {@code Clock} bean, this starter must still contribute its own {@code
 * outboxClock} and wire {@code outboxWriter} to it — not back off and leave the by-name injection
 * dangling.
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
                  AipersimmonDddOutboxAutoConfiguration.class,
                  AipersimmonDddOutboxJdbcAutoConfiguration.class))
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
