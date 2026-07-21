package com.aipersimmon.ddd.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aipersimmon.ddd.outbox.AipersimmonDddOutboxAutoConfiguration;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Verifies the auto-configuration wires the per-event {@link RoutingOutboxDispatcher} as
 * the outbox's single dispatcher when a KafkaTemplate is present, and registers the
 * consumer bridge only when it is enabled <em>and</em> at least one event is
 * {@code @Externalized} — all without contacting a broker. The fixture package holds one
 * externalized and one local event; {@code scan-packages} points the scan at it (the
 * context runner has no auto-configuration packages of its own).
 */
class AutoConfigurationWiringTest {

    private static final String FIXTURES = "com.aipersimmon.ddd.messaging.kafka.wiringfixture";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AipersimmonDddMessagingKafkaAutoConfiguration.class,
                    // provides the IntegrationEventCatalog the consumer bridge needs
                    AipersimmonDddOutboxAutoConfiguration.class))
            .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
            // KafkaProperties is the connection-settings source the transport builds its own
            // String producer/consumer factories from (Boot's KafkaAutoConfiguration would
            // supply it in production); the mock KafkaTemplate above is the "a Kafka transport
            // is present" gate.
            .withBean(KafkaProperties.class, KafkaProperties::new)
            .withPropertyValues("aipersimmon.ddd.integration.scan-packages=" + FIXTURES);

    @Test
    void registersRoutingDispatcherAndNoConsumerByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(OutboxDispatcher.class);
            assertThat(context.getBean(OutboxDispatcher.class)).isInstanceOf(RoutingOutboxDispatcher.class);
            assertThat(context).doesNotHaveBean(KafkaIntegrationEventListener.class);
        });
    }

    @Test
    void registersConsumerBridgeWhenEnabledAndAnEventIsExternalized() {
        runner.withPropertyValues("aipersimmon.ddd.messaging.kafka.consumer.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(KafkaIntegrationEventListener.class));
    }

    @Test
    void doesNotRegisterConsumerBridgeWhenNothingIsExternalized() {
        // Scan a package with no @Externalized event: the transport is idle, so even with the
        // consumer enabled the bridge is not registered (an empty topic set cannot subscribe).
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AipersimmonDddMessagingKafkaAutoConfiguration.class,
                        AipersimmonDddOutboxAutoConfiguration.class))
                .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
                .withBean(KafkaProperties.class, KafkaProperties::new)
                .withPropertyValues(
                        "aipersimmon.ddd.integration.scan-packages=com.aipersimmon.ddd.messaging.kafka.nonesuch",
                        "aipersimmon.ddd.messaging.kafka.consumer.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(RoutingOutboxDispatcher.class);
                    assertThat(context).doesNotHaveBean(KafkaIntegrationEventListener.class);
                    assertThat(context.getBean(ExternalizedRoutes.class).isEmpty()).isTrue();
                });
    }

    @Test
    void registersADedicatedContainerFactoryAndNoGlobalErrorHandler() {
        // The dead-lettering error handler lives on the transport's own container factory, not
        // as a global CommonErrorHandler bean — so Boot never applies it to the application's
        // other listeners on the default factory (issue-00029 (b)).
        runner.withPropertyValues("aipersimmon.ddd.messaging.kafka.consumer.enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("aipersimmonKafkaListenerContainerFactory");
                    assertThat(context.getBean("aipersimmonKafkaListenerContainerFactory"))
                            .isInstanceOf(ConcurrentKafkaListenerContainerFactory.class);
                    assertThat(context).doesNotHaveBean(CommonErrorHandler.class);
                });
    }

    @Test
    void noConsumerInfrastructureWhenTheConsumerIsDisabled() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(CommonErrorHandler.class);
            assertThat(context).doesNotHaveBean("aipersimmonKafkaListenerContainerFactory");
            assertThat(context).doesNotHaveBean("aipersimmonKafkaConsumerFactory");
        });
    }

    @Test
    void failsLoudWhenSeveralTransactionManagersAndNoneIsSelected() {
        // issue-00029 (c): with more than one transaction manager and no explicit choice, a bare
        // @Transactional would bind to whichever is primary and could commit the inbox on one
        // manager while the side effect rolls back on another. The consumer now refuses to start.
        runner.withPropertyValues("aipersimmon.ddd.messaging.kafka.consumer.enabled=true")
                .withBean("txA", PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean("txB", PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("aipersimmon.ddd.messaging.kafka.consumer.transaction-manager");
                });
    }

    @Test
    void failsLoudWhenAnInboxIsConfiguredButNoTransactionManagerIsPresent() {
        // issue-00029 (c) edge: the inbox insert must be transactional, so a configured Inbox
        // with no transaction manager is a misconfiguration (a handler failure would leave the
        // inbox marked and drop the event), not a silent run-without-a-transaction.
        runner.withPropertyValues("aipersimmon.ddd.messaging.kafka.consumer.enabled=true")
                .withBean(com.aipersimmon.ddd.application.Inbox.class, () -> messageKey -> false)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("Inbox is configured");
                });
    }

    @Test
    void usesTheNamedTransactionManagerWhenSeveralArePresent() {
        runner.withPropertyValues(
                        "aipersimmon.ddd.messaging.kafka.consumer.enabled=true",
                        "aipersimmon.ddd.messaging.kafka.consumer.transaction-manager=txA")
                .withBean("txA", PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean("txB", PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .run(context -> assertThat(context).hasSingleBean(KafkaIntegrationEventListener.class));
    }

    @Test
    void backsOffWhenNoKafkaTemplateIsPresent() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AipersimmonDddMessagingKafkaAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(OutboxDispatcher.class));
    }

    @Test
    void routingDispatcherWinsWhenInProcessPropertyIsAlsoSet() {
        runner.withPropertyValues("aipersimmon.ddd.outbox.dispatch=in-process")
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxDispatcher.class);
                    assertThat(context.getBean(OutboxDispatcher.class))
                            .isInstanceOf(RoutingOutboxDispatcher.class);
                });
    }
}
