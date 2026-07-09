package com.aipersimmon.ddd.events.spring;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.application.IntegrationEvents;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures Spring-backed, in-process, synchronous publishers for both
 * domain and integration events when the application does not define its own.
 * Adding this module to a Spring Boot application is enough to wire them.
 */
@AutoConfiguration
public class AipersimmonDddEventsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DomainEvents.class)
    public DomainEvents domainEvents(ApplicationEventPublisher publisher) {
        return new SpringDomainEvents(publisher);
    }

    /**
     * In-process synchronous publisher for integration events. Provided only when
     * the outbox starter is absent, so that adding the outbox makes it the
     * integration-event transport instead. An application can always override with
     * its own {@link IntegrationEvents} bean.
     */
    @Bean
    @ConditionalOnMissingBean(IntegrationEvents.class)
    @ConditionalOnMissingClass("com.aipersimmon.ddd.outbox.jdbc.OutboxWriter")
    public IntegrationEvents integrationEvents(ApplicationEventPublisher publisher) {
        return new SpringIntegrationEvents(publisher);
    }
}
