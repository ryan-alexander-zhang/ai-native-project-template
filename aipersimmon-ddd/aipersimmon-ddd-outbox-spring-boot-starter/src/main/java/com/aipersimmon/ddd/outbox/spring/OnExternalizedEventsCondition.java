package com.aipersimmon.ddd.outbox.spring;

import com.aipersimmon.ddd.integration.IntegrationEvent;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches only when the application declares at least one {@code @Externalized} integration event —
 * that is, only when anything is actually meant to leave this process.
 *
 * <p>Two callers depend on that question, which is why it lives here beside the scanner rather than
 * in either of them. The outbox uses it to gate its external-reach guard: with nothing
 * externalized, a transport-less dispatcher is a perfectly good configuration and must not fail
 * startup. The Kafka starter uses it to gate the consumer bridge: with zero externalized events
 * there is no topic to subscribe to, and a {@code @KafkaListener} with an empty topic set fails
 * startup — so the bridge is simply not registered and the transport stays idle (see the idle WARN
 * there).
 *
 * <p>Scans with the same {@link IntegrationEventScanner} that builds the catalog and the routes, so
 * "which events are externalized" is decided one way everywhere.
 */
public class OnExternalizedEventsCondition implements Condition {

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    String scanPackages =
        context.getEnvironment().getProperty("aipersimmon.ddd.integration.scan-packages", "");
    return IntegrationEventScanner.scan(context.getBeanFactory(), scanPackages).stream()
        .anyMatch(type -> IntegrationEvent.externalizedTarget(type).isPresent());
  }
}
