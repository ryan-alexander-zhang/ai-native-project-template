package com.aipersimmon.ddd.outbox.spring;

import com.aipersimmon.ddd.inbox.Inbox;
import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.integration.IntegrationEventCatalog;
import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog;
import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog.Key;
import com.aipersimmon.ddd.outbox.DefaultFailureClassifier;
import com.aipersimmon.ddd.outbox.FailureClassifier;
import com.aipersimmon.ddd.outbox.LoggingOutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Selects the single outbox {@link OutboxDispatcher}, independently of how the outbox is stored.
 * Exactly one dispatcher is wired (the relay injects one), so the choices are mutually exclusive:
 * the default republishes each relayed event in process; {@code
 * aipersimmon.ddd.outbox.dispatch=logging} instead logs it and delivers nothing. A messaging
 * starter (for example {@code -messaging-kafka}) can order itself before this class to register a
 * broker-backed dispatcher that wins over both defaults here — which is why each bean here is
 * guarded by {@code @ConditionalOnMissingBean} — and it works with any storage backend because this
 * dispatch wiring carries no persistence. To deliver an event more than one way (fan-out) or route
 * by type, define your own {@code OutboxDispatcher} bean that composes the others; all beans here
 * back off.
 *
 * <p>Dispatch selection is <strong>fail-closed</strong> for events that were meant to leave the
 * process. Because the relay marks a row sent whenever {@code dispatch} returns normally, a
 * transport that silently goes nowhere is indistinguishable from one that works — so no default
 * here discards events, and {@link #aipersimmonDddExternalReachGuard} refuses to start an
 * application whose {@code @Externalized} events have no way out.
 *
 * <p>The storage starter ({@code aipersimmon-ddd-outbox-jdbc}, {@code -outbox-mybatis-plus}, ...)
 * orders itself after this class so the chosen dispatcher bean exists when its relay is built.
 */
@AutoConfiguration
@EnableConfigurationProperties(OutboxProperties.class)
public class AipersimmonDddOutboxAutoConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(AipersimmonDddOutboxAutoConfiguration.class);

  /**
   * The default {@link IntegrationEventCatalog}: maps each inbound {@code (type, version)} to its
   * local class, so a consumer never loads the producer's class by name. Auto-populated by scanning
   * for {@link IntegrationEvent} implementations, keyed by each class's {@code (name, version)}
   * from its required {@link EventType} — the same pair a published instance stamps on the wire. A
   * scanned event with no {@link EventType} fails startup, as do two classes that declare the same
   * {@code (name, version)} (a contract clash — one would otherwise silently shadow the other).
   * There is no class-name fallback: an unregistered pair is a miss and the caller dead-letters it.
   * Override this bean to add mappings the scan cannot see — dynamic, third-party, or historical
   * revisions kept for migration.
   *
   * <p>Scans the application's own packages ({@code AutoConfigurationPackages}) plus any listed in
   * {@code aipersimmon.ddd.integration.scan-packages} (comma-separated). The latter is needed when
   * integration events live outside the application's package — for example a shared {@code
   * contracts} module two microservices depend on — since those are not covered by the
   * auto-configuration packages.
   */
  @Bean
  @ConditionalOnMissingBean(IntegrationEventCatalog.class)
  public IntegrationEventCatalog integrationEventCatalog(
      BeanFactory beanFactory,
      @Value("${aipersimmon.ddd.integration.scan-packages:}") String scanPackages) {
    Map<Key, Class<? extends IntegrationEvent>> byTypeAndVersion = new HashMap<>();
    for (Class<? extends IntegrationEvent> c :
        IntegrationEventScanner.scan(beanFactory, scanPackages)) {
      register(byTypeAndVersion, c);
    }
    return new RegistryIntegrationEventCatalog(byTypeAndVersion);
  }

  /**
   * Registers one integration event class under its {@code (name, version)} ({@link
   * IntegrationEvent#eventTypeOf} / {@link IntegrationEvent#eventVersionOf}). The same class
   * scanned twice (overlapping packages) is a no-op, but two different classes claiming the same
   * {@code (name, version)} fail fast: a silent shadow would deserialize a message into the wrong
   * class. Two classes sharing a name but with different versions are allowed — that is how a
   * type's revisions coexist.
   */
  static void register(
      Map<Key, Class<? extends IntegrationEvent>> byTypeAndVersion,
      Class<? extends IntegrationEvent> type) {
    Key key = new Key(IntegrationEvent.eventTypeOf(type), IntegrationEvent.eventVersionOf(type));
    Class<? extends IntegrationEvent> existing = byTypeAndVersion.putIfAbsent(key, type);
    if (existing != null && !existing.equals(type)) {
      throw new IllegalStateException(
          "duplicate integration event (type '"
              + key.type()
              + "', version "
              + key.version()
              + "): declared by both "
              + existing.getName()
              + " and "
              + type.getName()
              + "; give one a distinct @EventType name or version");
    }
  }

  /**
   * The default {@link FailureClassifier} the relay uses to tell a retryable dispatch failure from
   * a hopeless one. Storage-agnostic, so it lives here rather than in a storage starter. Override
   * the bean to refine the rules for your transport.
   */
  @Bean
  @ConditionalOnMissingBean(FailureClassifier.class)
  public FailureClassifier outboxFailureClassifier() {
    return new DefaultFailureClassifier();
  }

  /**
   * The default dispatcher: republish each relayed message in process. It is the right default
   * because it is the <em>correct</em> delivery for a LOCAL event (one with no
   * {@code @Externalized} annotation), which is what an application without a messaging starter
   * has. Every event still reaches its {@code @EventListener} handlers, just asynchronously via the
   * relay.
   *
   * <p>It is deliberately not "log and forget". A dispatcher that returns normally is treated by
   * the relay as having delivered, so a fallback that only logs turns a missing transport into
   * archived, undelivered events — see the guard below for the half of this that in-process cannot
   * fix.
   *
   * <p>With an {@link Inbox} bean present, the dispatcher absorbs the relay's redeliveries itself
   * (the same dedup the Kafka bridge gives brokered delivery); without one it says so loudly,
   * because "the framework dedups for me" is exactly the assumption a handler author brings over
   * from the Kafka path. An inbox without a transaction manager is refused at startup: a dedup
   * record that can outlive its failed delivery converts every crash into a lost event, which is
   * strictly worse than the duplicate it was meant to prevent.
   */
  @Bean
  @ConditionalOnProperty(
      name = "aipersimmon.ddd.outbox.dispatch",
      havingValue = "in-process",
      matchIfMissing = true)
  @ConditionalOnMissingBean(OutboxDispatcher.class)
  public OutboxDispatcher inProcessOutboxDispatcher(
      ApplicationEventPublisher publisher,
      ObjectProvider<ObjectMapper> objectMapper,
      IntegrationEventCatalog catalog,
      ObjectProvider<Inbox> inbox,
      ObjectProvider<PlatformTransactionManager> transactionManager) {
    ObjectMapper mapper = objectMapper.getIfAvailable(ObjectMapper::new);
    Inbox dedup = inbox.getIfAvailable();
    if (dedup == null) {
      log.warn(
          "aipersimmon-ddd outbox in-process dispatch has NO inbox: a relay redelivery reaches "
              + "@EventListener handlers again, so every handler must tolerate its own earlier "
              + "success. Add an inbox backend (aipersimmon-ddd-inbox-jdbc or "
              + "aipersimmon-ddd-inbox-mybatis-plus) to deduplicate redeliveries here, as the "
              + "Kafka consumer bridge does.");
      return new InProcessOutboxDispatcher(publisher, mapper, catalog);
    }
    PlatformTransactionManager ptm = transactionManager.getIfAvailable();
    if (ptm == null) {
      throw new IllegalStateException(
          "An Inbox bean is present but no PlatformTransactionManager is: the in-process outbox "
              + "dispatcher must run the inbox check and the handlers in one transaction, or a "
              + "failed delivery leaves a dedup record behind and its retry is dropped as a "
              + "duplicate. Provide a transaction manager (spring-boot-starter-jdbc does), or "
              + "remove the inbox.");
    }
    return new InProcessOutboxDispatcher(
        publisher, mapper, catalog, dedup, new TransactionTemplate(ptm));
  }

  /**
   * Opt-in only ({@code aipersimmon.ddd.outbox.dispatch=logging}): exercises the store-and-forward
   * path without delivering anything. Every relayed row is logged and then marked sent, so this
   * discards integration events by design — useful to watch the relay work, never a fallback. It
   * used to be the default that applied whenever no other dispatcher was found, which made a
   * forgotten transport indistinguishable from a working one.
   */
  @Bean
  @ConditionalOnProperty(name = "aipersimmon.ddd.outbox.dispatch", havingValue = "logging")
  @ConditionalOnMissingBean(OutboxDispatcher.class)
  public OutboxDispatcher loggingOutboxDispatcher() {
    log.warn(
        "aipersimmon-ddd outbox dispatch=logging: relayed integration events are logged and marked "
            + "sent WITHOUT being delivered anywhere. Intended for smoke-testing the relay only — "
            + "unset aipersimmon.ddd.outbox.dispatch for in-process delivery, or add a messaging "
            + "starter for a broker.");
    return new LoggingOutboxDispatcher();
  }

  /**
   * Fail-loud guard for the case no dispatcher can rescue: the application declares
   * {@code @Externalized} events — events it has said belong to another process's diet — but the
   * active dispatcher cannot reach an external target ({@link
   * OutboxDispatcher#reachesExternalTargets()}). Without this, those events are written to the
   * outbox, dispatched to a dead end, and marked sent: no exception, no dead letter, no consumer
   * lag, nothing that monitoring can see. The downstream simply never hears from us.
   *
   * <p>This is the sibling of the Kafka starter's durable-transport guard, which only exists when a
   * {@code KafkaTemplate} does — the case where messaging was never added at all was the gap it
   * could not cover. Checking here needs no transport on the classpath, only the outbox itself.
   *
   * <p>{@link OnExternalizedEventsCondition} scopes it: with nothing externalized, an in-process
   * dispatcher is a complete and correct configuration. It also only throws when a dispatcher bean
   * actually exists, leaving a partial context (a slice test) alone. Set {@code
   * aipersimmon.ddd.outbox.allow-unreachable-external-events=true} to proceed anyway — for a local
   * run of a service whose broker is not up, where losing those events is understood and accepted.
   */
  @Bean
  @Conditional(OnExternalizedEventsCondition.class)
  public SmartInitializingSingleton aipersimmonDddExternalReachGuard(
      ObjectProvider<OutboxDispatcher> dispatchers, OutboxProperties properties) {
    return () -> {
      OutboxDispatcher active = dispatchers.getIfAvailable();
      if (active == null || active.reachesExternalTargets()) {
        return;
      }
      if (properties.isAllowUnreachableExternalEvents()) {
        log.warn(
            "aipersimmon-ddd outbox: the application declares @Externalized integration event(s) but "
                + "the active OutboxDispatcher is '{}', which cannot reach an external target. Those "
                + "events will be marked sent WITHOUT leaving this process. Allowed because "
                + "aipersimmon.ddd.outbox.allow-unreachable-external-events=true.",
            active.getClass().getName());
        return;
      }
      throw new IllegalStateException(
          "aipersimmon-ddd outbox: the application declares @Externalized integration event(s), but "
              + "the active OutboxDispatcher is '"
              + active.getClass().getName()
              + "', which cannot deliver to an external target. The relay would mark each of those "
              + "events sent without it ever leaving this process — a silent loss with no exception, "
              + "no dead letter and no consumer lag to alert on. Either add a messaging starter (e.g. "
              + "aipersimmon-ddd-messaging-kafka) so a broker-backed dispatcher takes over, or define "
              + "your own OutboxDispatcher bean, or drop @Externalized from the event(s) to keep them "
              + "LOCAL (in-process) on purpose. To accept the loss knowingly — a local run without a "
              + "broker — set aipersimmon.ddd.outbox.allow-unreachable-external-events=true.");
    };
  }
}
