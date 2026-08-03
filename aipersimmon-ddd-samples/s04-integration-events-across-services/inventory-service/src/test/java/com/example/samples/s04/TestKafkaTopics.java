package com.example.samples.s04;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The topic this service consumes, and its dead-letter topic.
 *
 * <p>Provisioning the DLT is not test scaffolding — the library's documentation is explicit that it
 * does not create it and deliberately does not probe for it, and that a missing DLT means a poison
 * record cannot be dead-lettered, so the error handler seeks back and the partition retries it
 * forever. Watching consumer lag is the only signal. Creating it here mirrors what a deployment owes.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestKafkaTopics {

  @Bean
  NewTopic orderingEvents(@Value("${inventory.ordering-events-topic}") String topic) {
    return TopicBuilder.name(topic).partitions(1).replicas(1).build();
  }

  @Bean
  NewTopic orderingEventsDeadLetter(@Value("${inventory.ordering-events-topic}") String topic) {
    return TopicBuilder.name(topic + ".DLT").partitions(1).replicas(1).build();
  }
}
