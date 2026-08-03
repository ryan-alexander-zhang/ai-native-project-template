package com.example.samples.s21;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Both topics this service subscribes to, and a dead-letter topic for each.
 *
 * <p>One DLT per topic is not optional book-keeping: the recoverer publishes to {@code <topic>.DLT},
 * the library does not create it and deliberately does not probe for it, and a missing one means a
 * poison record cannot be dead-lettered — so the error handler seeks back and that partition retries
 * the same record forever, with consumer lag as the only signal. A consumer subscribed to two topics
 * owes two of them, which is one of the quieter costs of a topic-per-revision move.
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

  @Bean
  NewTopic legacyOrderingEvents(@Value("${inventory.legacy-events-topic}") String topic) {
    return TopicBuilder.name(topic).partitions(1).replicas(1).build();
  }

  @Bean
  NewTopic legacyOrderingEventsDeadLetter(
      @Value("${inventory.legacy-events-topic}") String topic) {
    return TopicBuilder.name(topic + ".DLT").partitions(1).replicas(1).build();
  }
}
