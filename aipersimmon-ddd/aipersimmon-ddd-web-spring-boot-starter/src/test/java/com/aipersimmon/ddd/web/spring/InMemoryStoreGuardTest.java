package com.aipersimmon.ddd.web.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.web.spi.IdempotencyStore;
import com.aipersimmon.ddd.web.spi.StoredResponse;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enabling idempotency, replay protection or rate limiting expresses an intent to stop something.
 * An in-memory store keeps its state per JVM, so a second instance silently stops honouring that
 * intent — the guard makes the substitution visible, and refusable (issue-00058).
 */
@ExtendWith(OutputCaptureExtension.class)
class InMemoryStoreGuardTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AipersimmonDddWebAutoConfiguration.class));

  @Test
  void anEnabledConcernOnAnInMemoryStoreWarnsWithWhatBreaksAndHowToFixIt(CapturedOutput output) {
    runner
        .withPropertyValues("aipersimmon.ddd.web.idempotency.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(IdempotencyStore.class))
                  .isInstanceOf(InMemoryIdempotencyStore.class);
              assertThat(output.getAll())
                  .as("names the concern and what actually goes wrong")
                  .contains("idempotency")
                  .contains("the side effect runs twice")
                  .as("and names the remedy, not just the problem")
                  .contains("aipersimmon-ddd-web-store-redis");
            });
  }

  @Test
  void everyEnabledConcernIsReportedInOneMessage(CapturedOutput output) {
    runner
        .withPropertyValues(
            "aipersimmon.ddd.web.idempotency.enabled=true",
            "aipersimmon.ddd.web.replay.enabled=true",
            "aipersimmon.ddd.web.rate-limit.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(output.getAll()).contains("3 enabled concern(s)");
              assertThat(output.getAll()).contains("replayed successfully");
              assertThat(output.getAll()).contains("multiplied by the instance count");
            });
  }

  @Test
  void aDisabledConcernIsNotReported(CapturedOutput output) {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(output.getAll())
              .as("nothing is enabled, so nothing is degraded")
              .doesNotContain("enabled concern(s)");
        });
  }

  @Test
  void anApplicationSuppliedStoreSilencesTheGuard(CapturedOutput output) {
    runner
        .withPropertyValues("aipersimmon.ddd.web.idempotency.enabled=true")
        .withUserConfiguration(SharedStoreConfig.class)
        .run(
            context -> {
              assertThat(context.getBean(IdempotencyStore.class)).isInstanceOf(SharedStore.class);
              assertThat(output.getAll())
                  .as("a real store is the fix, so there is nothing to report")
                  .doesNotContain("enabled concern(s)");
            });
  }

  @Test
  void refusingTheFallbackFailsStartupInsteadOfWarning() {
    runner
        .withPropertyValues(
            "aipersimmon.ddd.web.idempotency.enabled=true",
            "aipersimmon.ddd.web.allow-in-memory-stores=false")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasMessageContaining("allow-in-memory-stores=false")
                    .hasMessageContaining("idempotency"));
  }

  @Test
  void refusingTheFallbackStillStartsWhenARealStoreIsPresent() {
    runner
        .withPropertyValues(
            "aipersimmon.ddd.web.idempotency.enabled=true",
            "aipersimmon.ddd.web.allow-in-memory-stores=false")
        .withUserConfiguration(SharedStoreConfig.class)
        .run(
            context ->
                assertThat(context)
                    .as("the flag rejects the fallback, not the concern")
                    .hasNotFailed());
  }

  /** Stands in for a {@code -web-store-*} backend: only its type matters here. */
  private static final class SharedStore implements IdempotencyStore {
    @Override
    public Optional<StoredResponse> find(String key) {
      return Optional.empty();
    }

    @Override
    public boolean saveIfAbsent(String key, StoredResponse response, Duration ttl) {
      return true;
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class SharedStoreConfig {
    @Bean
    IdempotencyStore sharedStore() {
      return new SharedStore();
    }
  }
}
