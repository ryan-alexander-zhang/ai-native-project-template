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
import com.aipersimmon.ddd.outbox.OutboxProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
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
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Wires the Kafka integration-event transport with <strong>per-event</strong> routing. When a
 * {@link KafkaTemplate} is present it registers the {@link RoutingOutboxDispatcher} as the outbox's
 * single {@link OutboxDispatcher} (ordered before the outbox auto-configuration so it wins over the
 * logging default). The router keeps LOCAL events (the default — no {@code @Externalized}) in
 * process and sends only {@code @Externalized} events to their named topic; installing this starter
 * no longer puts every event on the broker. The reach/topic map is the {@link ExternalizedRoutes}
 * bean, built by scanning the application's integration events and resolving each target's {@code
 * ${property}} placeholders. If the starter is present but no event is {@code @Externalized}, a
 * WARN is logged and everything stays LOCAL — the transport is idle, startup is not failed.
 *
 * <p>When {@code aipersimmon.ddd.messaging.kafka.consumer.enabled=true} <em>and</em> at least one
 * event is {@code @Externalized}, it also registers the {@link KafkaIntegrationEventListener}
 * consumer bridge (subscribed to the externalized topic set), wiring in the {@link Inbox} if one is
 * available, plus a {@link DefaultErrorHandler} that gives a failed consume bounded
 * exponential-backoff retries and then dead-letters the record to {@code <topic>.DLT} — instead of
 * Spring Kafka's default of retrying with no backoff and then silently skipping. A permanent
 * failure (an unknown event type, a malformed payload) is not retried; it goes straight to the DLT.
 * Boot applies the single {@link CommonErrorHandler} bean to the default listener container. Every
 * bean is overridable by the application.
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
   * The externalization routing table, built once at startup by scanning the application's
   * integration events, keeping those annotated {@code @Externalized} and resolving each target's
   * {@code ${property}} placeholders against configuration. When empty (the starter is present but
   * nothing is externalized) a WARN is logged so a likely missing annotation is visible, without
   * failing startup or silently externalizing everything.
   */
  @Bean
  @ConditionalOnBean(KafkaTemplate.class)
  @ConditionalOnMissingBean
  public ExternalizedRoutes externalizedRoutes(
      BeanFactory beanFactory,
      Environment environment,
      @Value("${aipersimmon.ddd.integration.scan-packages:}") String scanPackages) {
    Map<Key, String> topicByEvent = new HashMap<>();
    for (Class<? extends IntegrationEvent> type :
        IntegrationEventScanner.scan(beanFactory, scanPackages)) {
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
      log.warn(
          "aipersimmon-ddd-messaging-kafka is on the classpath but no integration event is "
              + "@Externalized: every event stays LOCAL (in-process) and nothing is published to "
              + "Kafka. If that is intended, ignore this; otherwise annotate the events that must "
              + "cross the broker, e.g. @Externalized(\"ordering.events\").");
    } else {
      log.info("integration event externalization routes -> topics {}", (Object) routes.topics());
    }
    return routes;
  }

  /**
   * Fail-loud guard. When the application declares {@code @Externalized} events and a Kafka
   * transport is wired, those events reach the broker only if the active {@link IntegrationEvents}
   * publisher is durable — i.e. it writes each event to the transactional outbox the relay drains
   * ({@link DurableIntegrationEvents}). If an in-process (non-durable) publisher is active instead,
   * {@code @Externalized} events would be published in process and <em>silently never leave the
   * JVM</em>. This runs after all singletons are instantiated and, if it finds a non-durable
   * publisher active, fails startup with a concrete remedy rather than letting the misconfiguration
   * surface only as missing messages in production.
   *
   * <p>Scoped by {@link OnExternalizedEventsCondition} (only when something is actually
   * externalized) and {@code @ConditionalOnBean(KafkaTemplate)} (only when a real Kafka transport
   * is present). It throws only when a publisher bean exists and is non-durable; an incomplete
   * context with no {@link IntegrationEvents} bean at all (e.g. a slice test) is left alone.
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
                + active.getClass().getName()
                + "', which is in-process and not durable. @Externalized "
                + "events published through it never reach Kafka. Add a durable outbox module "
                + "(e.g. aipersimmon-ddd-outbox-mybatis-plus or aipersimmon-ddd-outbox-jdbc) so its "
                + "transactional-outbox writer becomes the IntegrationEvents transport, or remove "
                + "@Externalized to keep those events LOCAL (in-process) on purpose.");
      }
    };
  }

  /**
   * Startup WARN when the relay's worst-case per-poll budget can outlive its lease. The relay
   * dispatches a batch one row at a time and blocks on each broker ack up to {@code
   * producer.send-timeout-ms}, so a whole poll of stalled sends takes up to {@code batch-size ×
   * send-timeout}; if that exceeds {@code relay.lock-at-most-for} the ShedLock lease can expire
   * mid-poll and a second instance can dispatch the same rows concurrently. The shipped defaults
   * (batch 100 × 30s = 50min &lt; 60min) satisfy this, so this only fires when a custom
   * configuration breaks the invariant — a WARN, not a failure, because the worst case needs a
   * sustained broker outage and the operator may accept it knowingly. Gated like the durable guard:
   * only with a Kafka transport and actual {@code @Externalized} events. {@link OutboxProperties}
   * is optional (via {@link ObjectProvider}) so a Kafka-consumer-only app without an outbox is left
   * alone.
   */
  @Bean
  @ConditionalOnBean(KafkaTemplate.class)
  @Conditional(OnExternalizedEventsCondition.class)
  public SmartInitializingSingleton aipersimmonDddOutboxLeaseBudgetCheck(
      ObjectProvider<OutboxProperties> outboxProperties,
      KafkaMessagingProperties properties,
      @Value("${aipersimmon.ddd.outbox.relay.lock-at-most-for:PT60M}") String lockAtMostFor) {
    return () -> {
      OutboxProperties outbox = outboxProperties.getIfAvailable();
      if (outbox == null) {
        return;
      }
      // ISO-8601 (the default and the form the ShedLock annotations feed on). If a deployment
      // overrides it with an unparseable value the lease budget can't be checked — skip the
      // advisory WARN rather than fail startup over it.
      Duration lease;
      try {
        lease = Duration.parse(lockAtMostFor);
      } catch (RuntimeException e) {
        return;
      }
      long worstCaseMs = (long) outbox.getBatchSize() * properties.getProducer().getSendTimeoutMs();
      long leaseMs = lease.toMillis();
      if (worstCaseMs > leaseMs) {
        log.warn(
            "aipersimmon-ddd outbox relay budget: worst-case poll = batch-size ({}) × "
                + "producer.send-timeout-ms ({}ms) = {}ms, which exceeds relay.lock-at-most-for "
                + "({}ms). Under a sustained broker outage the ShedLock lease can expire mid-poll and "
                + "a second instance may dispatch the same rows concurrently. Lower batch-size or "
                + "send-timeout, or raise relay.lock-at-most-for, so batch-size × send-timeout stays "
                + "below the lease.",
            outbox.getBatchSize(),
            properties.getProducer().getSendTimeoutMs(),
            worstCaseMs,
            leaseMs);
      }
    };
  }

  /**
   * The single {@link OutboxDispatcher} the relay uses: a {@link RoutingOutboxDispatcher} that
   * keeps LOCAL events in process (an {@link InProcessOutboxDispatcher} leg) and sends
   * {@code @Externalized} events to their topic (a {@link KafkaOutboxDispatcher} leg).
   * {@code @ConditionalOnMissingBean} so an application can still supply its own.
   */
  @Bean
  @ConditionalOnBean(KafkaTemplate.class)
  @ConditionalOnMissingBean(OutboxDispatcher.class)
  public OutboxDispatcher routingOutboxDispatcher(
      KafkaTemplate<String, String> kafkaTemplate,
      KafkaMessagingProperties properties,
      ExternalizedRoutes routes,
      ApplicationEventPublisher publisher,
      ObjectProvider<ObjectMapper> objectMapper,
      IntegrationEventCatalog catalog) {
    OutboxDispatcher localLeg =
        new InProcessOutboxDispatcher(
            publisher, objectMapper.getIfAvailable(ObjectMapper::new), catalog);
    KafkaOutboxDispatcher externalLeg =
        new KafkaOutboxDispatcher(
            kafkaTemplate, Duration.ofMillis(properties.getProducer().getSendTimeoutMs()));
    return new RoutingOutboxDispatcher(localLeg, externalLeg, routes);
  }

  @Bean
  @ConditionalOnProperty(
      name = "aipersimmon.ddd.messaging.kafka.consumer.enabled",
      havingValue = "true")
  @Conditional(OnExternalizedEventsCondition.class)
  @ConditionalOnMissingBean
  public KafkaIntegrationEventListener kafkaIntegrationEventListener(
      ApplicationEventPublisher publisher,
      ObjectProvider<ObjectMapper> objectMapper,
      ObjectProvider<Inbox> inbox,
      IntegrationEventCatalog catalog,
      ConfigurableListableBeanFactory beanFactory,
      KafkaMessagingProperties properties) {
    // Which event types have a local @EventListener; records of any other type are dropped
    // before the inbox (nothing would handle them). Opt out to handle-everything when the
    // application consumes via a mechanism the scan cannot see.
    LocallyHandledEventTypes localHandlers =
        properties.getConsumer().isSkipLocallyUnhandled()
            ? LocallyHandledEventTypes.scan(beanFactory)
            : LocallyHandledEventTypes.handlingEverything();
    return new KafkaIntegrationEventListener(
        publisher,
        objectMapper.getIfAvailable(ObjectMapper::new),
        inbox.getIfAvailable(),
        catalog,
        localHandlers);
  }

  /**
   * The consumer's error handler: retry a failed consume with bounded exponential backoff, then
   * hand the record to the {@link DeadLetterPublishingRecoverer}, which publishes it to {@code
   * <topic>.DLT}. Only present when the consumer is enabled and a {@link KafkaTemplate} exists to
   * publish to the dead-letter topic. Boot's container-factory configurer applies this single
   * {@link CommonErrorHandler} bean to the listener container automatically.
   */
  @Bean
  @ConditionalOnBean(KafkaTemplate.class)
  @ConditionalOnProperty(
      name = "aipersimmon.ddd.messaging.kafka.consumer.enabled",
      havingValue = "true")
  @Conditional(OnExternalizedEventsCondition.class)
  @ConditionalOnMissingBean(CommonErrorHandler.class)
  public DefaultErrorHandler kafkaErrorHandler(
      KafkaTemplate<String, String> kafkaTemplate, KafkaMessagingProperties properties) {
    // Publish to "<topic>.DLT", keyed the same way as the source so an aggregate's
    // dead letters keep to one partition. The destination is set explicitly (rather
    // than left to the recoverer's default) so the DLT topic name is the documented
    // one and does not depend on a library default.
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
    return buildErrorHandler(recoverer, properties.getConsumer());
  }

  /**
   * Builds the error handler with three failure tiers, so an environment outage is not mistaken for
   * a poison message. Package-private so it can be unit-tested without a broker.
   *
   * <ul>
   *   <li><strong>Poison</strong> (the message is bad — unknown type, malformed, unparseable):
   *       marked not-retryable, so it skips the backoff and is dead-lettered at once. Same causes
   *       the outbox's {@code DefaultFailureClassifier} treats as permanent.
   *   <li><strong>Systemic</strong> (the environment is down — a {@link DataAccessException}:
   *       DataSource unavailable, pool exhausted, …): retried <strong>indefinitely</strong> at
   *       {@code systemicBackoffIntervalMs} and <strong>never</strong> dead-lettered. The partition
   *       waits at the record until recovery, so healthy messages are not flooded into the DLT and
   *       per-aggregate order is preserved.
   *   <li><strong>Everything else</strong> (ambiguous): the bounded exponential backoff, then the
   *       DLT — a safety net so an unforeseen always-failing record cannot block a partition
   *       forever.
   * </ul>
   */
  static DefaultErrorHandler buildErrorHandler(
      ConsumerRecordRecoverer recoverer, KafkaMessagingProperties.Consumer consumer) {
    KafkaMessagingProperties.Consumer.Retry retry = consumer.getRetry();
    ExponentialBackOffWithMaxRetries boundedBackOff =
        new ExponentialBackOffWithMaxRetries(retry.getMaxRetries());
    boundedBackOff.setInitialInterval(retry.getInitialIntervalMs());
    boundedBackOff.setMultiplier(retry.getMultiplier());
    boundedBackOff.setMaxInterval(retry.getMaxIntervalMs());
    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, boundedBackOff);
    // Poison: no number of retries conjures a local class for an unknown (type, version),
    // reparses a malformed payload, or adds a required attribute a record never had —
    // dead-letter them at once (see also boundary #5 of the integration-event contract).
    handler.addNotRetryableExceptions(
        UnknownIntegrationEventException.class,
        MalformedIntegrationEventException.class,
        JsonProcessingException.class);
    // Systemic: give infrastructure failures an unlimited backoff so they never reach the
    // recoverer (never dead-lettered); a null result falls back to the bounded backoff above.
    BackOff systemicBackOff =
        new FixedBackOff(consumer.getSystemicBackoffIntervalMs(), FixedBackOff.UNLIMITED_ATTEMPTS);
    handler.setBackOffFunction(
        (record, exception) -> isSystemicFailure(exception) ? systemicBackOff : null);
    return handler;
  }

  /**
   * Whether the failure signals a down environment (retry forever, never DLT) rather than a bad
   * message. Walks the cause chain — the container wraps the listener's exception in a {@code
   * ListenerExecutionFailedException} — and matches {@link DataAccessException} (the whole Spring
   * JDBC/DAO family: connection failures, pool exhaustion, transient faults).
   */
  private static boolean isSystemicFailure(Exception exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof DataAccessException) {
        return true;
      }
    }
    return false;
  }
}
