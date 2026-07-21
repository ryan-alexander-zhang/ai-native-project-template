package com.aipersimmon.ddd.messaging.kafka;

import com.aipersimmon.ddd.application.Inbox;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.integration.IntegrationEventCatalog;
import com.aipersimmon.ddd.integration.MalformedIntegrationEventException;
import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog.Key;
import com.aipersimmon.ddd.integration.UnknownIntegrationEventException;
import com.aipersimmon.ddd.outbox.AipersimmonDddOutboxAutoConfiguration;
import com.aipersimmon.ddd.outbox.InProcessOutboxDispatcher;
import com.aipersimmon.ddd.outbox.IntegrationEventScanner;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.apache.kafka.common.TopicPartition;

/**
 * Wires the Kafka integration-event transport with <strong>per-event</strong> routing.
 * When a {@link KafkaTemplate} is present it registers the {@link RoutingOutboxDispatcher}
 * as the outbox's single {@link OutboxDispatcher} (ordered before the outbox
 * auto-configuration so it wins over the logging default). The router keeps LOCAL events
 * (the default — no {@code @Externalized}) in process and sends only {@code @Externalized}
 * events to their named topic; installing this starter no longer puts every event on the
 * broker. The reach/topic map is the {@link ExternalizedRoutes} bean, built by scanning the
 * application's integration events and resolving each target's {@code ${property}}
 * placeholders. If the starter is present but no event is {@code @Externalized}, a WARN is
 * logged and everything stays LOCAL — the transport is idle, startup is not failed.
 *
 * <p>When {@code aipersimmon.ddd.messaging.kafka.consumer.enabled=true} <em>and</em> at
 * least one event is {@code @Externalized}, it also registers the
 * {@link KafkaIntegrationEventListener} consumer bridge (subscribed to the externalized
 * topic set), wiring in the {@link Inbox} if one is available, plus a
 * {@link DefaultErrorHandler} that gives a failed consume bounded exponential-backoff
 * retries and then dead-letters the record to {@code <topic>.DLT} — instead of Spring
 * Kafka's default of retrying with no backoff and then silently skipping. A permanent
 * failure (an unknown event type, a malformed payload) is not retried; it goes straight to
 * the DLT. Boot applies the single {@link CommonErrorHandler} bean to the default listener
 * container. Every bean is overridable by the application.
 */
@AutoConfiguration(
        after = KafkaAutoConfiguration.class,
        before = AipersimmonDddOutboxAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
@EnableConfigurationProperties(KafkaMessagingProperties.class)
public class AipersimmonDddMessagingKafkaAutoConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(AipersimmonDddMessagingKafkaAutoConfiguration.class);

    /**
     * The externalization routing table, built once at startup by scanning the
     * application's integration events, keeping those annotated {@code @Externalized} and
     * resolving each target's {@code ${property}} placeholders against configuration. When
     * empty (the starter is present but nothing is externalized) a WARN is logged so a
     * likely missing annotation is visible, without failing startup or silently
     * externalizing everything.
     */
    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnMissingBean
    public ExternalizedRoutes externalizedRoutes(BeanFactory beanFactory, Environment environment,
            @Value("${aipersimmon.ddd.integration.scan-packages:}") String scanPackages) {
        Map<Key, String> topicByEvent = new HashMap<>();
        for (Class<? extends IntegrationEvent> type : IntegrationEventScanner.scan(beanFactory, scanPackages)) {
            Optional<String> target = IntegrationEvent.externalizedTarget(type);
            if (target.isPresent()) {
                String topic = environment.resolveRequiredPlaceholders(target.get());
                topicByEvent.put(
                        new Key(IntegrationEvent.eventTypeOf(type), IntegrationEvent.eventVersionOf(type)),
                        topic);
            }
        }
        ExternalizedRoutes routes = new ExternalizedRoutes(topicByEvent);
        if (routes.isEmpty()) {
            log.warn("aipersimmon-ddd-messaging-kafka is on the classpath but no integration event is "
                    + "@Externalized: every event stays LOCAL (in-process) and nothing is published to "
                    + "Kafka. If that is intended, ignore this; otherwise annotate the events that must "
                    + "cross the broker, e.g. @Externalized(\"ordering.events\").");
        } else {
            log.info("integration event externalization routes -> topics {}", (Object) routes.topics());
        }
        return routes;
    }

    /**
     * The single {@link OutboxDispatcher} the relay uses: a {@link RoutingOutboxDispatcher}
     * that keeps LOCAL events in process (an {@link InProcessOutboxDispatcher} leg) and
     * sends {@code @Externalized} events to their topic (a {@link KafkaOutboxDispatcher}
     * leg). {@code @ConditionalOnMissingBean} so an application can still supply its own.
     */
    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnMissingBean(OutboxDispatcher.class)
    public OutboxDispatcher routingOutboxDispatcher(KafkaTemplate<String, String> kafkaTemplate,
            KafkaMessagingProperties properties, ExternalizedRoutes routes,
            ApplicationEventPublisher publisher, ObjectProvider<ObjectMapper> objectMapper,
            IntegrationEventCatalog catalog) {
        OutboxDispatcher localLeg = new InProcessOutboxDispatcher(
                publisher, objectMapper.getIfAvailable(ObjectMapper::new), catalog);
        KafkaOutboxDispatcher externalLeg = new KafkaOutboxDispatcher(kafkaTemplate,
                Duration.ofMillis(properties.getProducer().getSendTimeoutMs()));
        return new RoutingOutboxDispatcher(localLeg, externalLeg, routes);
    }

    @Bean
    @ConditionalOnProperty(name = "aipersimmon.ddd.messaging.kafka.consumer.enabled", havingValue = "true")
    @Conditional(OnExternalizedEventsCondition.class)
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
    @Conditional(OnExternalizedEventsCondition.class)
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                 KafkaMessagingProperties properties) {
        // Publish to "<topic>.DLT", keyed the same way as the source so an aggregate's
        // dead letters keep to one partition. The destination is set explicitly (rather
        // than left to the recoverer's default) so the DLT topic name is the documented
        // one and does not depend on a library default.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        return buildErrorHandler(recoverer, properties.getConsumer().getRetry());
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
        // No number of retries conjures a local class for an unknown (type, version),
        // reparses a malformed payload, or adds a required attribute a record never had —
        // dead-letter them at once (see also boundary #5 of the integration-event
        // contract: unknown inbound (type, version) -> DLT).
        handler.addNotRetryableExceptions(
                UnknownIntegrationEventException.class,
                MalformedIntegrationEventException.class,
                JsonProcessingException.class);
        return handler;
    }
}
