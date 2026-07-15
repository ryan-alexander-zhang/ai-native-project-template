package com.aipersimmon.ddd.messaging.kafka;

import com.aipersimmon.ddd.application.Inbox;
import com.aipersimmon.ddd.integration.IntegrationEventCatalog;
import com.aipersimmon.ddd.outbox.AipersimmonDddOutboxAutoConfiguration;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Wires the Kafka integration-event transport. When a {@link KafkaTemplate} is
 * present it registers the {@link KafkaOutboxDispatcher} as the outbox's
 * {@link OutboxDispatcher} (ordered before the outbox auto-configuration so it wins
 * over the logging default). When
 * {@code aipersimmon.ddd.messaging.kafka.consumer.enabled=true} it also registers
 * the {@link KafkaIntegrationEventListener} consumer bridge, wiring in the
 * {@link Inbox} if one is available. Every bean is overridable by the application.
 */
@AutoConfiguration(
        after = KafkaAutoConfiguration.class,
        before = AipersimmonDddOutboxAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
@EnableConfigurationProperties(KafkaMessagingProperties.class)
public class AipersimmonDddMessagingKafkaAutoConfiguration {

    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnMissingBean(OutboxDispatcher.class)
    public OutboxDispatcher kafkaOutboxDispatcher(KafkaTemplate<String, String> kafkaTemplate,
                                                  KafkaMessagingProperties properties) {
        return new KafkaOutboxDispatcher(kafkaTemplate, properties.getTopic());
    }

    @Bean
    @ConditionalOnProperty(name = "aipersimmon.ddd.messaging.kafka.consumer.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public KafkaIntegrationEventListener kafkaIntegrationEventListener(
            ApplicationEventPublisher publisher, ObjectProvider<ObjectMapper> objectMapper,
            ObjectProvider<Inbox> inbox, IntegrationEventCatalog catalog) {
        return new KafkaIntegrationEventListener(
                publisher, objectMapper.getIfAvailable(ObjectMapper::new),
                inbox.getIfAvailable(), catalog);
    }
}
