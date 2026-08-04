package com.aipersimmon.ddd.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.outbox.spring.AipersimmonDddOutboxAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Who the durable-transport guard is for, and who it used to catch by mistake.
 *
 * <p>The guard's job is real: with {@code @Externalized} events, a Kafka transport, and a
 * non-durable publisher, those events are published in process and silently never leave the JVM. No
 * exception, no dead letter, no consumer lag — startup failure is the only moment anyone can be
 * told.
 *
 * <p>What it could not see is that {@code @Externalized} means two different things. On a publisher
 * it routes; on a consumer it <em>is</em> the subscription declaration, because that is how the
 * bridge learns its topic set. So a service that only consumes satisfied every condition the guard
 * checks and was told to add a durable outbox module — which it then provisioned three tables for
 * and never wrote a row into (issue-00161, hit independently by four samples). The framework cannot
 * infer the difference: there is no static evidence of a call to {@code IntegrationEvents.publish}.
 * So the application states it, and the default states the strict thing.
 */
class DurableTransportGuardTest {

  private static final String FIXTURES = "com.aipersimmon.ddd.messaging.kafka.wiringfixture";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AipersimmonDddMessagingKafkaAutoConfiguration.class,
                  AipersimmonDddOutboxAutoConfiguration.class))
          .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
          // A non-durable publisher: the in-process shape a service has before an outbox module is
          // added. It does not implement DurableIntegrationEvents, which is the whole signal.
          .withBean(IntegrationEvents.class, () -> (event, context) -> {})
          .withPropertyValues("aipersimmon.ddd.integration.scan-packages=" + FIXTURES);

  /**
   * A publisher with nowhere durable to publish still fails, and now the message covers both cases.
   */
  @Test
  void apublisherWithoutADurableOutboxStillFailsToStart() {
    runner.run(
        context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasStackTraceContaining("is in-process and not durable")
              // The remedy for a publisher.
              .hasStackTraceContaining("Add a durable outbox module")
              // And the one for a consumer, which is what was missing: the old message sent a
              // consume-only service to add an outbox it would never write to.
              .hasStackTraceContaining(
                  "aipersimmon.ddd.messaging.kafka.publishes-externalized-events=false");
        });
  }

  /**
   * A service that says it only consumes starts, with no outbox anywhere.
   *
   * <p>Which is the point of the fix: its {@code @Externalized} declarations are subscriptions, so
   * there is nothing to lose by not having a durable publisher — there is nothing to publish.
   */
  @Test
  void aconsumeOnlyServiceStartsOnceItSaysSo() {
    runner
        .withPropertyValues("aipersimmon.ddd.messaging.kafka.publishes-externalized-events=false")
        .run(context -> assertThat(context).hasNotFailed());
  }
}
