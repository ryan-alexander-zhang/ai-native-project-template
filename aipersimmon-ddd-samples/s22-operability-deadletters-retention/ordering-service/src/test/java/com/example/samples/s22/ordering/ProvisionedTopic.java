package com.example.samples.s22.ordering;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Provisions the destination topic at startup — the correct deployment, imported by the tests that
 * want delivery to work.
 *
 * <p>A {@code NewTopic} bean is how Spring's {@code KafkaAdmin} creates a topic at context refresh.
 * Real deployments do it with terraform, a broker admin, or a startup job; the mechanism is
 * uninteresting and the sequencing is not — the topic has to exist before the first record, which is
 * why this is a bean and not something a test calls.
 */
@TestConfiguration(proxyBeanMethods = false)
class ProvisionedTopic {

  @Bean
  NewTopic orderingEvents(@Value("${ordering.events-topic}") String topic) {
    return TopicBuilder.name(topic).partitions(1).replicas(1).build();
  }
}
