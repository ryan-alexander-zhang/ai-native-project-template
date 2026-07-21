package com.aipersimmon.ddd.messaging.kafka;

import com.aipersimmon.ddd.application.DurableIntegrationEvents;
import com.aipersimmon.ddd.application.Inbox;
import com.aipersimmon.ddd.application.IntegrationEvents;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

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
 * <p><strong>The transport shares the host's Kafka infrastructure and isolates only what its
 * own protocol decides.</strong> The point is not to stand up a parallel Kafka setup, but to
 * stop the starter and the host application from fighting over the same global policies. So it
 * <em>shares</em> the broker connection (bootstrap servers, SSL, SASL — from {@link
 * KafkaProperties} and, under a service connection like Testcontainers or Docker Compose,
 * {@link KafkaConnectionDetails}), Boot's factory customizers, and Boot's listener operational
 * config (concurrency, ack mode, poll timeout, observation). It <em>owns</em> only the three
 * things its protocol determines, on its own dedicated beans:
 * <ul>
 *   <li>the <strong>wire format</strong>: a dedicated {@link ProducerFactory}/{@link
 *       KafkaTemplate} and {@link ConsumerFactory} with the serializer fixed to {@code String}
 *       (already-serialized JSON values with {@code ce_} headers), so the application's own
 *       {@code spring.kafka.*} serializer cannot change it — a {@code JsonSerializer} there
 *       would double-encode the value;</li>
 *   <li>the <strong>error handling</strong>: a dedicated {@link
 *       ConcurrentKafkaListenerContainerFactory} carrying the dead-lettering handler, so the
 *       retry/DLT policy is scoped to this listener and is neither imposed on the application's
 *       other listeners nor displaced by an error handler the application defines;</li>
 *   <li>the <strong>consumer transaction boundary</strong>: a {@link TransactionOperations}
 *       bound to an explicitly resolved (not newly created) database transaction manager, so
 *       the inbox check and handling commit or roll back together even when several transaction
 *       managers exist.</li>
 * </ul>
 *
 * <p>When {@code aipersimmon.ddd.messaging.kafka.consumer.enabled=true} <em>and</em> at
 * least one event is {@code @Externalized}, it also registers the
 * {@link KafkaIntegrationEventListener} consumer bridge (subscribed to the externalized
 * topic set) on the dedicated container factory, wiring in the {@link Inbox} if one is
 * available. A failed consume gets bounded exponential-backoff retries and is then
 * dead-lettered to {@code <topic>.DLT} — instead of Spring Kafka's default of retrying with
 * no backoff and then silently skipping. A permanent failure (an unknown event type, a
 * malformed payload) is not retried; it goes straight to the DLT. Every bean is overridable
 * by the application.
 */
@AutoConfiguration(
        after = KafkaAutoConfiguration.class,
        before = AipersimmonDddOutboxAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
@EnableConfigurationProperties(KafkaMessagingProperties.class)
public class AipersimmonDddMessagingKafkaAutoConfiguration {

    /** The transport's own producer factory bean — String serializers, not the app's. */
    static final String PRODUCER_FACTORY = "aipersimmonKafkaProducerFactory";
    /** The transport's own {@link KafkaTemplate} bean — the dispatcher publishes through it. */
    static final String KAFKA_TEMPLATE = "aipersimmonKafkaTemplate";
    /** The transport's own consumer factory bean — String deserializers, not the app's. */
    static final String CONSUMER_FACTORY = "aipersimmonKafkaConsumerFactory";
    /**
     * The transport's own listener container factory bean, carrying the dead-lettering error
     * handler. Referenced from {@link KafkaIntegrationEventListener}'s {@code @KafkaListener}
     * so the transport's error handling never touches the application's other listeners.
     */
    static final String LISTENER_CONTAINER_FACTORY = "aipersimmonKafkaListenerContainerFactory";

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
     * Fail-loud guard (issue-00044). When the application declares {@code @Externalized} events and a
     * Kafka transport is wired, those events reach the broker only if the active {@link IntegrationEvents}
     * publisher is durable — i.e. it writes each event to the transactional outbox the relay drains
     * ({@link DurableIntegrationEvents}). If an in-process (non-durable) publisher is active instead,
     * {@code @Externalized} events would be published in process and <em>silently never leave the
     * JVM</em>. This runs after all singletons are instantiated and, if it finds a non-durable publisher
     * active, fails startup with a concrete remedy rather than letting the misconfiguration surface only
     * as missing messages in production.
     *
     * <p>Scoped by {@link OnExternalizedEventsCondition} (only when something is actually externalized)
     * and {@code @ConditionalOnBean(KafkaTemplate)} (only when a real Kafka transport is present). It
     * throws only when a publisher bean exists and is non-durable; an incomplete context with no
     * {@link IntegrationEvents} bean at all (e.g. a slice test) is left alone.
     */
    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @Conditional(OnExternalizedEventsCondition.class)
    public SmartInitializingSingleton aipersimmonDddDurableTransportGuard(
            ObjectProvider<IntegrationEvents> integrationEvents) {
        return () -> {
            IntegrationEvents active = integrationEvents.getIfAvailable();
            if (active != null && !(active instanceof DurableIntegrationEvents)) {
                throw new IllegalStateException(
                        "aipersimmon-ddd-messaging-kafka: the application declares @Externalized integration "
                        + "event(s) and a Kafka transport, but the active IntegrationEvents publisher is '"
                        + active.getClass().getName() + "', which is in-process and not durable. @Externalized "
                        + "events published through it never reach Kafka. Add a durable outbox module "
                        + "(e.g. aipersimmon-ddd-outbox-mybatis-plus or aipersimmon-ddd-outbox-jdbc) so its "
                        + "transactional-outbox writer becomes the IntegrationEvents transport, or remove "
                        + "@Externalized to keep those events LOCAL (in-process) on purpose.");
            }
        };
    }

    /**
     * The transport's own producer factory. It <em>shares</em> the host's Kafka connection
     * (bootstrap servers, security, SSL) — taken from Boot's {@link KafkaProperties} and, when a
     * service connection is active (Testcontainers, Docker Compose), from {@link
     * KafkaConnectionDetails} rather than the {@code spring.kafka.bootstrap-servers} property —
     * and Boot's {@link DefaultKafkaProducerFactoryCustomizer}s. It <em>owns</em> only the wire
     * format: the key/value serializer is fixed to {@link StringSerializer} because the dispatcher
     * hands the factory an already-serialized JSON string with {@code ce_} headers, so the
     * transport must not inherit whatever serializer the application set for its own usage — a
     * {@code JsonSerializer} in particular would double-encode the value.
     */
    @Bean(PRODUCER_FACTORY)
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnMissingBean(name = PRODUCER_FACTORY)
    public ProducerFactory<String, String> aipersimmonKafkaProducerFactory(
            KafkaProperties kafkaProperties, ObjectProvider<SslBundles> sslBundles,
            ObjectProvider<KafkaConnectionDetails> connectionDetails,
            ObjectProvider<DefaultKafkaProducerFactoryCustomizer> customizers) {
        Map<String, Object> config = kafkaProperties.buildProducerProperties(sslBundles.getIfAvailable());
        connectionDetails.ifAvailable(details ->
                config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, details.getProducerBootstrapServers()));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(config);
        customizers.orderedStream().forEach(customizer -> customizer.customize(factory));
        return factory;
    }

    /** The transport's own {@link KafkaTemplate} over its String producer factory. */
    @Bean(KAFKA_TEMPLATE)
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnMissingBean(name = KAFKA_TEMPLATE)
    public KafkaTemplate<String, String> aipersimmonKafkaTemplate(
            @Qualifier(PRODUCER_FACTORY) ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * The single {@link OutboxDispatcher} the relay uses: a {@link RoutingOutboxDispatcher}
     * that keeps LOCAL events in process (an {@link InProcessOutboxDispatcher} leg) and
     * sends {@code @Externalized} events to their topic (a {@link KafkaOutboxDispatcher}
     * leg over the transport's own {@link KafkaTemplate}). {@code @ConditionalOnMissingBean}
     * so an application can still supply its own.
     */
    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnMissingBean(OutboxDispatcher.class)
    public OutboxDispatcher routingOutboxDispatcher(
            @Qualifier(KAFKA_TEMPLATE) KafkaTemplate<String, String> kafkaTemplate,
            KafkaMessagingProperties properties, ExternalizedRoutes routes,
            ApplicationEventPublisher publisher, ObjectProvider<ObjectMapper> objectMapper,
            IntegrationEventCatalog catalog) {
        OutboxDispatcher localLeg = new InProcessOutboxDispatcher(
                publisher, objectMapper.getIfAvailable(ObjectMapper::new), catalog);
        KafkaOutboxDispatcher externalLeg = new KafkaOutboxDispatcher(kafkaTemplate,
                Duration.ofMillis(properties.getProducer().getSendTimeoutMs()));
        return new RoutingOutboxDispatcher(localLeg, externalLeg, routes);
    }

    /**
     * The transport's own consumer factory. Like the producer factory it <em>shares</em> the
     * host's connection ({@link KafkaProperties} + {@link KafkaConnectionDetails}) and Boot's
     * {@link DefaultKafkaConsumerFactoryCustomizer}s, and <em>owns</em> only the wire format: the
     * key/value deserializer is fixed to {@link StringDeserializer} so the consumer reconstructs
     * from the String body + {@code ce_} headers regardless of the application's own
     * {@code spring.kafka} deserializer choice. Typed {@code <Object, Object>} to mirror Boot's
     * default factory so {@link ConcurrentKafkaListenerContainerFactoryConfigurer} can configure it.
     */
    @Bean(CONSUMER_FACTORY)
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnProperty(name = "aipersimmon.ddd.messaging.kafka.consumer.enabled", havingValue = "true")
    @Conditional(OnExternalizedEventsCondition.class)
    @ConditionalOnMissingBean(name = CONSUMER_FACTORY)
    public ConsumerFactory<Object, Object> aipersimmonKafkaConsumerFactory(
            KafkaProperties kafkaProperties, ObjectProvider<SslBundles> sslBundles,
            ObjectProvider<KafkaConnectionDetails> connectionDetails,
            ObjectProvider<DefaultKafkaConsumerFactoryCustomizer> customizers) {
        Map<String, Object> config = kafkaProperties.buildConsumerProperties(sslBundles.getIfAvailable());
        connectionDetails.ifAvailable(details ->
                config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, details.getConsumerBootstrapServers()));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        DefaultKafkaConsumerFactory<Object, Object> factory = new DefaultKafkaConsumerFactory<>(config);
        customizers.orderedStream().forEach(customizer -> customizer.customize(factory));
        return factory;
    }

    /**
     * The transport's own listener container factory. It <em>shares</em> Boot's listener
     * operational configuration — concurrency, ack mode, poll timeout, observation, container
     * customizers from {@code spring.kafka.listener.*} — by running it through Boot's
     * {@link ConcurrentKafkaListenerContainerFactoryConfigurer} when present, and <em>owns</em>
     * only the error handling: the dead-lettering handler is set on this factory (overriding
     * whatever the configurer may have applied) and never published as a global
     * {@code CommonErrorHandler} bean, so it is neither imposed on the application's other Kafka
     * listeners nor displaced by an error handler the application defines (which would silently
     * drop the transport's own DLT). Typed {@code <Object, Object>} to match the configurer.
     */
    @Bean(LISTENER_CONTAINER_FACTORY)
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnProperty(name = "aipersimmon.ddd.messaging.kafka.consumer.enabled", havingValue = "true")
    @Conditional(OnExternalizedEventsCondition.class)
    @ConditionalOnMissingBean(name = LISTENER_CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> aipersimmonKafkaListenerContainerFactory(
            @Qualifier(CONSUMER_FACTORY) ConsumerFactory<Object, Object> consumerFactory,
            @Qualifier(KAFKA_TEMPLATE) KafkaTemplate<String, String> kafkaTemplate,
            KafkaMessagingProperties properties,
            ObjectProvider<ConcurrentKafkaListenerContainerFactoryConfigurer> configurer) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        ConcurrentKafkaListenerContainerFactoryConfigurer bootConfigurer = configurer.getIfAvailable();
        if (bootConfigurer != null) {
            bootConfigurer.configure(factory, consumerFactory);
        } else {
            factory.setConsumerFactory(consumerFactory);
        }
        // Publish to "<topic>.DLT", keyed the same way as the source so an aggregate's dead
        // letters keep to one partition. The destination is set explicitly (rather than left to
        // the recoverer's default) so the DLT topic name is the documented one. Set last so it
        // wins over any handler the configurer applied.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        factory.setCommonErrorHandler(buildErrorHandler(recoverer, properties.getConsumer().getRetry()));
        return factory;
    }

    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnProperty(name = "aipersimmon.ddd.messaging.kafka.consumer.enabled", havingValue = "true")
    @Conditional(OnExternalizedEventsCondition.class)
    @ConditionalOnMissingBean
    public KafkaIntegrationEventListener kafkaIntegrationEventListener(
            ApplicationEventPublisher publisher, ObjectProvider<ObjectMapper> objectMapper,
            ObjectProvider<Inbox> inbox, IntegrationEventCatalog catalog,
            ObjectProvider<PlatformTransactionManager> transactionManagers,
            @Value("${aipersimmon.ddd.messaging.kafka.consumer.transaction-manager:}") String transactionManagerName,
            BeanFactory beanFactory) {
        Inbox inboxBean = inbox.getIfAvailable();
        TransactionOperations transaction = resolveConsumerTransaction(
                transactionManagerName, transactionManagers, beanFactory, inboxBean != null);
        return new KafkaIntegrationEventListener(
                publisher, objectMapper.getIfAvailable(ObjectMapper::new),
                inboxBean, catalog, transaction);
    }

    /**
     * Resolves the transaction manager the consumer's inbox check and handling run in, so the
     * inbox insert and the handler's writes commit or roll back together. Explicit resolution
     * (rather than a bare {@code @Transactional}, which binds to whichever manager is primary)
     * is what fixes the multi-manager footgun: with several managers and none chosen, the
     * inbox could commit on one while the side effect rolls back on another, silently losing
     * the event.
     *
     * <ul>
     *   <li>a configured bean name wins (the explicit choice for a multi-manager application);</li>
     *   <li>otherwise the single manager is used;</li>
     *   <li>none present and an inbox is configured -> fail loud (the inbox insert must be
     *       transactional, so a missing manager is a misconfiguration, not a silent no-op);</li>
     *   <li>none present and no inbox -> no surrounding transaction (nothing to make atomic);</li>
     *   <li>several present and none chosen -> fail loud, naming the property to set.</li>
     * </ul>
     */
    private static TransactionOperations resolveConsumerTransaction(String transactionManagerName,
            ObjectProvider<PlatformTransactionManager> transactionManagers, BeanFactory beanFactory,
            boolean inboxPresent) {
        if (StringUtils.hasText(transactionManagerName)) {
            return new TransactionTemplate(
                    beanFactory.getBean(transactionManagerName, PlatformTransactionManager.class));
        }
        List<PlatformTransactionManager> present = transactionManagers.stream().toList();
        if (present.isEmpty()) {
            if (inboxPresent) {
                throw new IllegalStateException(
                        "aipersimmon-ddd-messaging-kafka: an Inbox is configured but no PlatformTransactionManager "
                        + "is present, so the inbox insert and the handling cannot commit or roll back together — a "
                        + "handler failure would leave the inbox marked and silently drop the event. Configure a "
                        + "database transaction manager (a JDBC/JPA DataSource brings one).");
            }
            return TransactionOperations.withoutTransaction();
        }
        if (present.size() == 1) {
            return new TransactionTemplate(present.get(0));
        }
        throw new IllegalStateException(
                "aipersimmon-ddd-messaging-kafka: the consumer's inbox check and handling must run in one "
                + "database transaction, but " + present.size() + " PlatformTransactionManager beans are present "
                + "and none was selected. Set aipersimmon.ddd.messaging.kafka.consumer.transaction-manager to the "
                + "bean name of the database transaction manager the inbox writes to.");
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
