package com.aipersimmon.ddd.outbox;

import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.integration.IntegrationEventTypeResolver;
import com.aipersimmon.ddd.integration.RegistryIntegrationEventTypeResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.core.type.filter.AssignableTypeFilter;

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
     * Maps each inbound logical event type to its local class, so a consumer never
     * loads the producer's class by name. Auto-populated by scanning for
     * {@link IntegrationEvent} implementations, keyed by simple class name (the
     * default {@code eventType()}); a custom logical type or a fully-qualified name
     * still resolves via the registry's fallback. Override this bean to register
     * custom type names explicitly.
     *
     * <p>Scans the application's own packages ({@code AutoConfigurationPackages}) plus
     * any listed in {@code aipersimmon.ddd.integration.scan-packages} (comma-separated).
     * The latter is needed when integration events live outside the application's
     * package — for example a shared {@code contracts} module two microservices depend
     * on — since those are not covered by the auto-configuration packages.
     */
    @Bean
    @ConditionalOnMissingBean(IntegrationEventTypeResolver.class)
    public IntegrationEventTypeResolver integrationEventTypeResolver(BeanFactory beanFactory,
            @Value("${aipersimmon.ddd.integration.scan-packages:}") String scanPackages) {
        Set<String> packages = new LinkedHashSet<>();
        if (AutoConfigurationPackages.has(beanFactory)) {
            packages.addAll(AutoConfigurationPackages.get(beanFactory));
        }
        for (String pkg : scanPackages.split(",")) {
            String trimmed = pkg.trim();
            if (!trimmed.isEmpty()) {
                packages.add(trimmed);
            }
        }
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(IntegrationEvent.class));
        Map<String, Class<? extends IntegrationEvent>> byType = new HashMap<>();
        for (String pkg : packages) {
            for (BeanDefinition def : scanner.findCandidateComponents(pkg)) {
                try {
                    Class<?> c = Class.forName(def.getBeanClassName());
                    if (IntegrationEvent.class.isAssignableFrom(c) && !c.isInterface()) {
                        byType.putIfAbsent(c.getSimpleName(), c.asSubclass(IntegrationEvent.class));
                    }
                } catch (ClassNotFoundException ignored) {
                    // skip a candidate that cannot be loaded
                }
            }
        }
        return new RegistryIntegrationEventTypeResolver(byType);
    }

    @Bean
    @ConditionalOnProperty(name = "aipersimmon.ddd.outbox.dispatch", havingValue = "in-process")
    @ConditionalOnMissingBean(OutboxDispatcher.class)
    public OutboxDispatcher inProcessOutboxDispatcher(ApplicationEventPublisher publisher,
                                                      ObjectProvider<ObjectMapper> objectMapper,
                                                      IntegrationEventTypeResolver typeResolver) {
        return new InProcessOutboxDispatcher(
                publisher, objectMapper.getIfAvailable(ObjectMapper::new), typeResolver);
    }

    @Bean
    @ConditionalOnMissingBean(OutboxDispatcher.class)
    public OutboxDispatcher loggingOutboxDispatcher() {
        return new LoggingOutboxDispatcher();
    }
}
