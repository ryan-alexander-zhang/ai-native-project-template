package com.aipersimmon.ddd.events.spring;

import com.aipersimmon.ddd.application.DomainEvents;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures a {@link DomainEvents} bean backed by Spring's event publisher
 * when the application does not define its own. Adding this module to a Spring
 * Boot application is enough to wire it.
 */
@AutoConfiguration
public class AipersimmonDddEventsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DomainEvents.class)
    public DomainEvents domainEvents(ApplicationEventPublisher publisher) {
        return new SpringDomainEvents(publisher);
    }
}
