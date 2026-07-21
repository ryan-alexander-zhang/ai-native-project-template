package com.aipersimmon.ddd.messaging.kafka;

import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.outbox.IntegrationEventScanner;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches only when the application declares at least one {@code @Externalized}
 * integration event. Gates the consumer bridge: with zero externalized events there is no
 * topic to subscribe to, so registering a {@code @KafkaListener} (which requires a
 * non-empty topic set) would fail startup — instead the bridge is simply not registered
 * and the transport stays idle (see the idle WARN in the auto-configuration).
 *
 * <p>Scans with the same {@link IntegrationEventScanner} that builds the catalog and the
 * routes, so "which events are externalized" is decided one way everywhere.
 */
public class OnExternalizedEventsCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String scanPackages = context.getEnvironment()
                .getProperty("aipersimmon.ddd.integration.scan-packages", "");
        return IntegrationEventScanner.scan(context.getBeanFactory(), scanPackages).stream()
                .anyMatch(type -> IntegrationEvent.externalizedTarget(type).isPresent());
    }
}
