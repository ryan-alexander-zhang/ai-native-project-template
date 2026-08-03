package com.example.samples.s05;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The ERP's topic and its dead-letter topic.
 *
 * <p>The DLT is provisioned here because the recoverer publishes to it and does not create it. Missing,
 * the publish fails, the error handler seeks back, and the partition retries the same rejected record
 * forever — with consumer lag as the only symptom. Creating it mirrors what the deployment owes.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestKafkaTopics {

  @Bean
  NewTopic erpTopic(@Value("${catalog.erp-topic}") String topic) {
    return TopicBuilder.name(topic).partitions(1).replicas(1).build();
  }

  @Bean
  NewTopic erpDeadLetterTopic(@Value("${catalog.erp-topic}") String topic) {
    return TopicBuilder.name(topic + ".DLT").partitions(1).replicas(1).build();
  }
}
