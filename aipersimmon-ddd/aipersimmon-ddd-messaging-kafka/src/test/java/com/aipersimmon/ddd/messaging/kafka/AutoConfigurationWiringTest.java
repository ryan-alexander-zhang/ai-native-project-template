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
 * Verifies the auto-configuration wires the Kafka dispatcher as the outbox's
 * dispatcher when a KafkaTemplate is present, and adds the consumer bridge only
 * when explicitly enabled — all without contacting a broker.
 */
class AutoConfigurationWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AipersimmonDddMessagingKafkaAutoConfiguration.class,
                    // provides the IntegrationEventCatalog the consumer bridge needs
                    AipersimmonDddOutboxAutoConfiguration.class))
            .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class));

    @Test
    void registersKafkaDispatcherAndNoConsumerByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(OutboxDispatcher.class);
            assertThat(context.getBean(OutboxDispatcher.class)).isInstanceOf(KafkaOutboxDispatcher.class);
            assertThat(context).doesNotHaveBean(KafkaIntegrationEventListener.class);
        });
    }

    @Test
    void registersConsumerBridgeWhenEnabled() {
        runner.withPropertyValues("aipersimmon.ddd.messaging.kafka.consumer.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(KafkaIntegrationEventListener.class));
    }

    @Test
    void registersDeadLetteringErrorHandlerWithTheConsumer() {
        runner.withPropertyValues("aipersimmon.ddd.messaging.kafka.consumer.enabled=true")
                .run(context -> {
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
                .withConfiguration(AutoConfigurations.of(AipersimmonDddMessagingKafkaAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(OutboxDispatcher.class));
    }

    @Test
    void kafkaDispatcherWinsWhenInProcessPropertyIsAlsoSet() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AipersimmonDddMessagingKafkaAutoConfiguration.class,
                        AipersimmonDddOutboxAutoConfiguration.class))
                .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
                .withPropertyValues("aipersimmon.ddd.outbox.dispatch=in-process")
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxDispatcher.class);
                    assertThat(context.getBean(OutboxDispatcher.class))
                            .isInstanceOf(KafkaOutboxDispatcher.class);
                });
    }
}
