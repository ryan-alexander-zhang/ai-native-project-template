package com.example.samples.s12.ordering;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The topic this service consumes, and its dead-letter topic.
 *
 * <p>Provisioning the DLT is not test scaffolding: the library does not create it and deliberately does not
 * probe for it, so a missing DLT means a poison record cannot be dead-lettered and the partition retries it
 * forever with consumer lag as the only signal. Creating it here mirrors what a deployment owes.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestKafkaTopics {

  @Bean
  NewTopic catalogEvents(@Value("${ordering.catalog-events-topic}") String topic) {
    return TopicBuilder.name(topic).partitions(1).replicas(1).build();
  }

  @Bean
  NewTopic catalogEventsDeadLetter(@Value("${ordering.catalog-events-topic}") String topic) {
    return TopicBuilder.name(topic + ".DLT").partitions(1).replicas(1).build();
  }
}
