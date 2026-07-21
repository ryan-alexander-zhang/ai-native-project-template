package com.aipersimmon.ddd.outbox;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.integration.IntegrationEventCatalog;
import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog;
import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog.Key;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * Selects the single outbox {@link OutboxDispatcher}, independently of how the
 * outbox is stored. Exactly one dispatcher is wired (the relay injects one), so the
 * choices are mutually exclusive: the default only logs; setting
 * {@code aipersimmon.ddd.outbox.dispatch=in-process} switches to republishing
 * events in process instead. A messaging starter (for example
 * {@code -messaging-kafka}) can order itself before this class to register a
 * broker-backed dispatcher that wins over both defaults here — including when the
 * in-process property is also set, which is why the in-process bean is itself
 * guarded by {@code @ConditionalOnMissingBean} — and it works with any storage
 * backend because this dispatch wiring carries no persistence. To deliver an event
 * more than one way (fan-out) or route by type, define your own
 * {@code OutboxDispatcher} bean that composes the others; all beans here back off.
 *
 * <p>The storage starter ({@code aipersimmon-ddd-outbox-jdbc},
 * {@code -outbox-mybatis-plus}, ...) orders itself after this class so the chosen
 * dispatcher bean exists when its relay is built.
 */
@AutoConfiguration
public class AipersimmonDddOutboxAutoConfiguration {

    /**
     * The default {@link IntegrationEventCatalog}: maps each inbound {@code (type,
     * version)} to its local class, so a consumer never loads the producer's class by
     * name. Auto-populated by scanning for {@link IntegrationEvent} implementations,
     * keyed by each class's {@code (name, version)} from its required {@link EventType}
     * — the same pair a published instance stamps on the wire. A scanned event with no
     * {@link EventType} fails startup, as do two classes that declare the same
     * {@code (name, version)} (a contract clash — one would otherwise silently shadow
     * the other). There is no class-name fallback: an unregistered pair is a miss and
     * the caller dead-letters it. Override this bean to add mappings the scan cannot
     * see — dynamic, third-party, or historical revisions kept for migration.
     *
     * <p>Scans the application's own packages ({@code AutoConfigurationPackages}) plus
     * any listed in {@code aipersimmon.ddd.integration.scan-packages} (comma-separated).
     * The latter is needed when integration events live outside the application's
     * package — for example a shared {@code contracts} module two microservices depend
     * on — since those are not covered by the auto-configuration packages.
     */
    @Bean
    @ConditionalOnMissingBean(IntegrationEventCatalog.class)
    public IntegrationEventCatalog integrationEventCatalog(BeanFactory beanFactory,
            @Value("${aipersimmon.ddd.integration.scan-packages:}") String scanPackages) {
        Map<Key, Class<? extends IntegrationEvent>> byTypeAndVersion = new HashMap<>();
        for (Class<? extends IntegrationEvent> c : IntegrationEventScanner.scan(beanFactory, scanPackages)) {
            register(byTypeAndVersion, c);
        }
        return new RegistryIntegrationEventCatalog(byTypeAndVersion);
    }

    /**
     * Registers one integration event class under its {@code (name, version)}
     * ({@link IntegrationEvent#eventTypeOf} / {@link IntegrationEvent#eventVersionOf}).
     * The same class scanned twice (overlapping packages) is a no-op, but two different
     * classes claiming the same {@code (name, version)} fail fast: a silent shadow would
     * deserialize a message into the wrong class. Two classes sharing a name but with
     * different versions are allowed — that is how a type's revisions coexist.
     */
    static void register(Map<Key, Class<? extends IntegrationEvent>> byTypeAndVersion,
                         Class<? extends IntegrationEvent> type) {
        Key key = new Key(IntegrationEvent.eventTypeOf(type), IntegrationEvent.eventVersionOf(type));
        Class<? extends IntegrationEvent> existing = byTypeAndVersion.putIfAbsent(key, type);
        if (existing != null && !existing.equals(type)) {
            throw new IllegalStateException(
                    "duplicate integration event (type '" + key.type() + "', version " + key.version()
                            + "): declared by both " + existing.getName() + " and " + type.getName()
                            + "; give one a distinct @EventType name or version");
        }
    }

    /**
     * The default {@link FailureClassifier} the relay uses to tell a retryable dispatch
     * failure from a hopeless one. Storage-agnostic, so it lives here rather than in a
     * storage starter. Override the bean to refine the rules for your transport.
     */
    @Bean
    @ConditionalOnMissingBean(FailureClassifier.class)
    public FailureClassifier outboxFailureClassifier() {
        return new DefaultFailureClassifier();
    }

    @Bean
    @ConditionalOnProperty(name = "aipersimmon.ddd.outbox.dispatch", havingValue = "in-process")
    @ConditionalOnMissingBean(OutboxDispatcher.class)
    public OutboxDispatcher inProcessOutboxDispatcher(ApplicationEventPublisher publisher,
                                                      ObjectProvider<ObjectMapper> objectMapper,
                                                      IntegrationEventCatalog catalog) {
        return new InProcessOutboxDispatcher(
                publisher, objectMapper.getIfAvailable(ObjectMapper::new), catalog);
    }

    @Bean
    @ConditionalOnMissingBean(OutboxDispatcher.class)
    public OutboxDispatcher loggingOutboxDispatcher() {
        return new LoggingOutboxDispatcher();
    }
}
