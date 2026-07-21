package com.aipersimmon.ddd.events.spring;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.application.IntegrationEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures Spring-backed, in-process, synchronous publishers for both domain and integration
 * events when the application does not define its own. Adding this module to a Spring Boot
 * application is enough to wire them.
 */
@AutoConfiguration
public class AipersimmonDddEventsAutoConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(AipersimmonDddEventsAutoConfiguration.class);

  @Bean
  @ConditionalOnMissingBean(DomainEvents.class)
  public DomainEvents domainEvents(ApplicationEventPublisher publisher) {
    return new SpringDomainEvents(publisher);
  }

  /**
   * In-process synchronous publisher for integration events — the <strong>fallback</strong>
   * transport, used only when no durable outbox writer has claimed the {@link IntegrationEvents}
   * port. Detection is by bean presence, not by knowing any concrete outbox class: each outbox
   * auto-configuration declares itself {@code @AutoConfigureBefore} this one, so when an outbox is
   * on the classpath its writer registers first and {@code @ConditionalOnMissingBean} makes this
   * bean back off (issue-00044). An application can also override with its own bean.
   *
   * <p>Note this publisher is <em>not</em> {@link
   * com.aipersimmon.ddd.application.DurableIntegrationEvents}: it does not persist events, so
   * {@code @Externalized} events published through it never reach an external broker — the Kafka
   * messaging starter fails startup rather than degrade silently.
   */
  @Bean
  @ConditionalOnMissingBean(IntegrationEvents.class)
  public IntegrationEvents integrationEvents(
      ApplicationEventPublisher publisher,
      @Value("${aipersimmon.ddd.integration.source:${spring.application.name:aipersimmon}}")
          String source) {
    log.info(
        "aipersimmon-ddd integration-event transport: in-process (local) — no durable outbox on "
            + "the classpath; @Externalized events cannot reach an external broker with this transport");
    return new SpringIntegrationEvents(publisher, source);
  }
}
