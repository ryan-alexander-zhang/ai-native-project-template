package com.aipersimmon.ddd.messaging.kafka;

import com.aipersimmon.ddd.application.Inbox;
import com.aipersimmon.ddd.integration.IntegrationEventCatalog;
import com.aipersimmon.ddd.integration.UnknownIntegrationEventException;
import com.aipersimmon.ddd.outbox.AipersimmonDddOutboxAutoConfiguration;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Wires the Kafka integration-event transport. When a {@link KafkaTemplate} is
 * present it registers the {@link KafkaOutboxDispatcher} as the outbox's
 * {@link OutboxDispatcher} (ordered before the outbox auto-configuration so it wins
 * over the logging default). When
 * {@code aipersimmon.ddd.messaging.kafka.consumer.enabled=true} it also registers
 * the {@link KafkaIntegrationEventListener} consumer bridge, wiring in the
 * {@link Inbox} if one is available, plus a {@link DefaultErrorHandler} that gives a
 * failed consume bounded exponential-backoff retries and then dead-letters the record
 * to {@code <topic>.DLT} — instead of Spring Kafka's default of retrying with no
 * backoff and then silently skipping. A permanent failure (an unknown event type, a
 * malformed payload) is not retried; it goes straight to the DLT. Boot applies the
 * single {@link CommonErrorHandler} bean to the default listener container. Every bean
 * is overridable by the application.
 */
@AutoConfiguration(
        after = KafkaAutoConfiguration.class,
        before = AipersimmonDddOutboxAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
@EnableConfigurationProperties(KafkaMessagingProperties.class)
public class AipersimmonDddMessagingKafkaAutoConfiguration {

    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnMissingBean(OutboxDispatcher.class)
    public OutboxDispatcher kafkaOutboxDispatcher(KafkaTemplate<String, String> kafkaTemplate,
                                                  KafkaMessagingProperties properties) {
        return new KafkaOutboxDispatcher(kafkaTemplate, properties.getTopic());
    }

    @Bean
    @ConditionalOnProperty(name = "aipersimmon.ddd.messaging.kafka.consumer.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public KafkaIntegrationEventListener kafkaIntegrationEventListener(
            ApplicationEventPublisher publisher, ObjectProvider<ObjectMapper> objectMapper,
            ObjectProvider<Inbox> inbox, IntegrationEventCatalog catalog) {
        return new KafkaIntegrationEventListener(
                publisher, objectMapper.getIfAvailable(ObjectMapper::new),
                inbox.getIfAvailable(), catalog);
    }

    /**
     * The consumer's error handler: retry a failed consume with bounded exponential
     * backoff, then hand the record to the {@link DeadLetterPublishingRecoverer}, which
     * publishes it to {@code <topic>.DLT}. Only present when the consumer is enabled and
     * a {@link KafkaTemplate} exists to publish to the dead-letter topic. Boot's
     * container-factory configurer applies this single {@link CommonErrorHandler} bean to
     * the listener container automatically.
     */
    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnProperty(name = "aipersimmon.ddd.messaging.kafka.consumer.enabled", havingValue = "true")
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                 KafkaMessagingProperties properties) {
        return buildErrorHandler(
                new DeadLetterPublishingRecoverer(kafkaTemplate), properties.getConsumer().getRetry());
    }

    /**
     * Builds the error handler from a recoverer and the retry settings. The permanent
     * failures are marked not-retryable so they skip the backoff and are dead-lettered on
     * the first failure — the same causes the outbox's {@code DefaultFailureClassifier}
     * treats as permanent, so both transports agree on what is hopeless. Package-private
     * so it can be unit-tested without a broker.
     */
    static DefaultErrorHandler buildErrorHandler(ConsumerRecordRecoverer recoverer,
                                                 KafkaMessagingProperties.Consumer.Retry retry) {
        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(retry.getMaxRetries());
        backOff.setInitialInterval(retry.getInitialIntervalMs());
        backOff.setMultiplier(retry.getMultiplier());
        backOff.setMaxInterval(retry.getMaxIntervalMs());
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // No number of retries conjures a local class for an unknown (type, version), nor
        // reparses a malformed payload — dead-letter them at once (see also boundary #5 of
        // the integration-event contract: unknown inbound (type, version) -> DLT).
        handler.addNotRetryableExceptions(
                UnknownIntegrationEventException.class, JsonProcessingException.class);
        return handler;
    }
}
