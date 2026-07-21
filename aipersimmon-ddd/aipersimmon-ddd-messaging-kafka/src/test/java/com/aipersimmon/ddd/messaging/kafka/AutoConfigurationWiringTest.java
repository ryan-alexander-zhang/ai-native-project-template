package com.aipersimmon.ddd.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aipersimmon.ddd.outbox.AipersimmonDddOutboxAutoConfiguration;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * Verifies the auto-configuration wires the per-event {@link RoutingOutboxDispatcher} as the
 * outbox's single dispatcher when a KafkaTemplate is present, and registers the consumer bridge
 * only when it is enabled <em>and</em> at least one event is {@code @Externalized} — all without
 * contacting a broker. The fixture package holds one externalized and one local event; {@code
 * scan-packages} points the scan at it (the context runner has no auto-configuration packages of
 * its own).
 */
class AutoConfigurationWiringTest {

  private static final String FIXTURES = "com.aipersimmon.ddd.messaging.kafka.wiringfixture";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AipersimmonDddMessagingKafkaAutoConfiguration.class,
                  // provides the IntegrationEventCatalog the consumer bridge needs
                  AipersimmonDddOutboxAutoConfiguration.class))
          .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
          .withPropertyValues("aipersimmon.ddd.integration.scan-packages=" + FIXTURES);

  @Test
  void registersRoutingDispatcherAndNoConsumerByDefault() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(OutboxDispatcher.class);
          assertThat(context.getBean(OutboxDispatcher.class))
              .isInstanceOf(RoutingOutboxDispatcher.class);
          assertThat(context).doesNotHaveBean(KafkaIntegrationEventListener.class);
        });
  }

  @Test
  void registersConsumerBridgeWhenEnabledAndAnEventIsExternalized() {
    runner
        .withPropertyValues("aipersimmon.ddd.messaging.kafka.consumer.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(KafkaIntegrationEventListener.class));
  }

  @Test
  void doesNotRegisterConsumerBridgeWhenNothingIsExternalized() {
    // Scan a package with no @Externalized event: the transport is idle, so even with the
    // consumer enabled the bridge is not registered (an empty topic set cannot subscribe).
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AipersimmonDddMessagingKafkaAutoConfiguration.class,
                AipersimmonDddOutboxAutoConfiguration.class))
        .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
        .withPropertyValues(
            "aipersimmon.ddd.integration.scan-packages=com.aipersimmon.ddd.messaging.kafka.nonesuch",
            "aipersimmon.ddd.messaging.kafka.consumer.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(RoutingOutboxDispatcher.class);
              assertThat(context).doesNotHaveBean(KafkaIntegrationEventListener.class);
              assertThat(context.getBean(ExternalizedRoutes.class).isEmpty()).isTrue();
            });
  }

  @Test
  void registersDeadLetteringErrorHandlerWithTheConsumer() {
    runner
        .withPropertyValues("aipersimmon.ddd.messaging.kafka.consumer.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(CommonErrorHandler.class);
              assertThat(context.getBean(CommonErrorHandler.class))
                  .isInstanceOf(DefaultErrorHandler.class);
            });
  }

  @Test
  void noErrorHandlerWhenTheConsumerIsDisabled() {
    runner.run(context -> assertThat(context).doesNotHaveBean(CommonErrorHandler.class));
  }

  @Test
  void backsOffWhenNoKafkaTemplateIsPresent() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(AipersimmonDddMessagingKafkaAutoConfiguration.class))
        .run(context -> assertThat(context).doesNotHaveBean(OutboxDispatcher.class));
  }

  @Test
  void routingDispatcherWinsWhenInProcessPropertyIsAlsoSet() {
    runner
        .withPropertyValues("aipersimmon.ddd.outbox.dispatch=in-process")
        .run(
            context -> {
              assertThat(context).hasSingleBean(OutboxDispatcher.class);
              assertThat(context.getBean(OutboxDispatcher.class))
                  .isInstanceOf(RoutingOutboxDispatcher.class);
            });
  }
}
