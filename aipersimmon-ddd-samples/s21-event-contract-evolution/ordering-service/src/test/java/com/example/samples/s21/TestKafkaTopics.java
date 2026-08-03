package com.example.samples.s21;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The topic this service publishes to.
 *
 * <p>Created up front rather than left to auto-creation on first send: a topic created by the send
 * itself is not visible to a consumer that already subscribed, until its metadata refresh — five
 * minutes by default — which reads exactly like "the message was never published".
 */
@TestConfiguration(proxyBeanMethods = false)
class TestKafkaTopics {

  @Bean
  NewTopic orderingEvents(@Value("${ordering.events-topic}") String topic) {
    return TopicBuilder.name(topic).partitions(1).replicas(1).build();
  }
}
