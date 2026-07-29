package com.aipersimmon.ddd.outbox.engine.autoconfigure;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.observability.NoOpStoreAndForwardTracer;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.EventDestinations;
import com.aipersimmon.ddd.outbox.FailureClassifier;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.RetryBackoff;
import com.aipersimmon.ddd.outbox.engine.cleanup.OutboxCleanup;
import com.aipersimmon.ddd.outbox.engine.observe.OutboxBacklog;
import com.aipersimmon.ddd.outbox.engine.observe.OutboxObserver;
import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay;
import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelayScheduler;
import com.aipersimmon.ddd.outbox.engine.relay.RelayLeases;
import com.aipersimmon.ddd.outbox.engine.store.OutboxStore;
import com.aipersimmon.ddd.outbox.engine.write.OutboxWriter;
import com.aipersimmon.ddd.outbox.spring.AipersimmonDddOutboxAutoConfiguration;
import com.aipersimmon.ddd.outbox.spring.OutboxProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the outbox writer, relay, scheduled trigger and retention cleanup once a storage backend
 * has contributed an {@link OutboxStore}. Everything here is storage-agnostic: the backend ({@code
 * aipersimmon-ddd-outbox-jdbc}, {@code aipersimmon-ddd-outbox-mybatis-plus}) supplies the store,
 * the dead-letter store and its read side, and nothing else.
 *
 * <p>Ordered after the backend registrations (so the {@link ConditionalOnBean} gates below see the
 * store), after the storage-agnostic {@link AipersimmonDddOutboxAutoConfiguration} that picks the
 * dispatcher, and before the in-process events fallback — so this durable writer claims the {@link
 * IntegrationEvents} port and the fallback backs off. Enables scheduling so the relay runs in the
 * background; an application can override any of these beans.
 */
@AutoConfiguration(
    afterName = {
      "com.aipersimmon.ddd.outbox.jdbc.AipersimmonDddOutboxJdbcAutoConfiguration",
      "com.aipersimmon.ddd.outbox.mybatisplus.AipersimmonDddOutboxMybatisPlusAutoConfiguration"
    },
    after = AipersimmonDddOutboxAutoConfiguration.class,
    // String form: this module does not depend on events-spring, and an absent target is ignored.
    beforeName = "com.aipersimmon.ddd.events.spring.AipersimmonDddEventsAutoConfiguration")
@EnableScheduling
public class AipersimmonDddOutboxEngineAutoConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(AipersimmonDddOutboxEngineAutoConfiguration.class);

  // Name-scoped so this component always contributes its own named clock and injects it by name,
  // rather than backing off when another component (process-manager, inbox) already registered a
  // Clock of the same type — which would leave the by-name `outboxClock` injections unresolved.
  @Bean
  @ConditionalOnMissingBean(name = "outboxClock")
  public Clock outboxClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnBean(OutboxStore.class)
  @ConditionalOnMissingBean(IntegrationEvents.class)
  public IntegrationEvents outboxWriter(
      OutboxStore outboxStore,
      ObjectProvider<ObjectMapper> objectMapper,
      Clock outboxClock,
      @Value("${aipersimmon.ddd.integration.source:${spring.application.name:aipersimmon}}")
          String source,
      ObjectProvider<StoreAndForwardTracer> tracer,
      ObjectProvider<EventDestinations> destinations,
      IdGenerator idGenerator) {
    log.info("aipersimmon-ddd integration-event transport: durable transactional outbox");
    // No transport starter means nothing is externalized, so ALL_IN_PROCESS is the truth here
    // rather than a degraded fallback: there is no target for the writer to have missed. When a
    // transport is installed it contributes the real resolver, and the startup guard on
    // @Externalized events refuses to run a deployment where those events have nowhere to go.
    return new OutboxWriter(
        outboxStore,
        objectMapper.getIfAvailable(ObjectMapper::new),
        outboxClock,
        source,
        destinations.getIfAvailable(() -> EventDestinations.ALL_IN_PROCESS),
        tracer.getIfAvailable(() -> NoOpStoreAndForwardTracer.INSTANCE),
        idGenerator::newId);
  }

  @Bean
  @ConditionalOnBean({OutboxStore.class, DeadLetterStore.class})
  @ConditionalOnMissingBean
  public OutboxRelay outboxRelay(
      OutboxStore outboxStore,
      OutboxDispatcher outboxDispatcher,
      DeadLetterStore deadLetterStore,
      FailureClassifier failureClassifier,
      Clock outboxClock,
      OutboxProperties properties,
      ObjectProvider<StoreAndForwardTracer> tracer,
      ObjectProvider<OutboxObserver> observer) {
    String workerId = properties.getRelay().getWorkerId();
    Duration lease = properties.getRelay().getLeaseDuration();
    return new OutboxRelay(
        outboxStore,
        outboxDispatcher,
        deadLetterStore,
        failureClassifier,
        new RetryBackoff(
            properties.getRetry().getBaseBackoffMs(), properties.getRetry().getMaxBackoffMs()),
        outboxClock,
        properties.getBatchSize(),
        properties.getMaxAttempts(),
        workerId.isBlank()
            ? RelayLeases.forThisProcess(lease)
            : RelayLeases.ownedBy(workerId, lease),
        tracer.getIfAvailable(() -> NoOpStoreAndForwardTracer.INSTANCE),
        observer.getIfAvailable(() -> OutboxObserver.NOOP));
  }

  /**
   * The scheduled trigger. Conditional so a deployment that relays from one dedicated instance — or
   * a test that drives the relay itself — can switch the schedule off without losing the relay. The
   * relay bean above stays either way.
   */
  @Bean
  @ConditionalOnBean(OutboxRelay.class)
  @ConditionalOnProperty(
      name = "aipersimmon.ddd.outbox.relay.enabled",
      havingValue = "true",
      matchIfMissing = true)
  @ConditionalOnMissingBean
  public OutboxRelayScheduler outboxRelayScheduler(OutboxRelay outboxRelay) {
    return new OutboxRelayScheduler(outboxRelay);
  }

  /**
   * The backlog read: how much is waiting and how long the oldest has waited. Registered here
   * rather than with the Micrometer beans because it is framework-free and useful on its own — an
   * operations endpoint or a custom probe can read it with no metrics library at all — and because
   * a condition in another auto-configuration can only see beans registered before it.
   */
  @Bean
  @ConditionalOnBean(OutboxStore.class)
  @ConditionalOnMissingBean
  public OutboxBacklog outboxBacklog(
      OutboxStore outboxStore, Clock outboxClock, OutboxProperties properties) {
    return new OutboxBacklog(outboxStore, outboxClock, properties.getMaxAttempts());
  }

  @Bean
  @ConditionalOnBean(OutboxStore.class)
  @ConditionalOnProperty(name = "aipersimmon.ddd.outbox.cleanup.enabled", havingValue = "true")
  @ConditionalOnMissingBean
  public OutboxCleanup outboxCleanup(
      OutboxStore outboxStore, Clock outboxClock, OutboxProperties properties) {
    return new OutboxCleanup(
        outboxStore, outboxClock, properties.getCleanup().getRetentionSeconds());
  }
}
