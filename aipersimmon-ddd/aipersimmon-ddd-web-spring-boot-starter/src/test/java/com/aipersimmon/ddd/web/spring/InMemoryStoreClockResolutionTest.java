package com.aipersimmon.ddd.web.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.web.spi.IdempotencyStore;
import com.aipersimmon.ddd.web.spi.RateLimiter;
import com.aipersimmon.ddd.web.spi.ReplayGuard;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The in-memory stores take whatever {@link Clock} the application provides, and fall back to the
 * system clock when it provides none. "None" is the only case that may fall back — but "several"
 * must not fail.
 *
 * <p>It did (issue-00063). Every storage component registers its own unqualified {@code Clock}
 * bean, so any application assembling more than one of them has several; asking for an optional
 * dependency with {@code getIfAvailable} throws in exactly that case, because the method that
 * tolerates ambiguity is {@code getIfUnique}. The result was that switching idempotency on in a
 * real application — one with an outbox, an inbox, a process manager — failed at startup with a
 * message about {@code Clock} that named nothing the operator had configured.
 *
 * <p>The library's own module tests never saw it: each assembles one component, so there was never
 * more than one clock. This test assembles the situation an application actually presents.
 */
class InMemoryStoreClockResolutionTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AipersimmonDddWebAutoConfiguration.class))
          .withPropertyValues(
              "aipersimmon.ddd.web.idempotency.enabled=true",
              "aipersimmon.ddd.web.replay.enabled=true",
              "aipersimmon.ddd.web.rate-limit.enabled=true");

  @Test
  void severalClocksDoNotBreakTheFallbackStores() {
    runner
        .withUserConfiguration(TwoClocks.class)
        .run(
            context -> {
              assertThat(context)
                  .as("several Clock beans is the normal shape of an assembled application")
                  .hasNotFailed();
              assertThat(context).hasSingleBean(IdempotencyStore.class);
              assertThat(context).hasSingleBean(ReplayGuard.class);
              assertThat(context).hasSingleBean(RateLimiter.class);
            });
  }

  @Test
  void noClockAtAllStillFallsBackToTheSystemClock() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(IdempotencyStore.class);
        });
  }

  /** Stands in for any two components that each contribute a clock. */
  @Configuration(proxyBeanMethods = false)
  static class TwoClocks {

    @Bean
    Clock outboxClock() {
      return Clock.systemUTC();
    }

    @Bean
    Clock processManagerClock() {
      return Clock.system(ZoneOffset.UTC);
    }
  }
}
