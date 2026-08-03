package com.example.samples.s04;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Creates the topic at startup, so the test's own consumer can be assigned a partition on its first
 * poll.
 *
 * <p>Without this the topic is auto-created by the relay's first send, <em>after</em> the consumer has
 * subscribed — and a consumer that subscribed to a topic that did not exist yet learns about it on the
 * next metadata refresh, which defaults to five minutes. The symptom is a test that reads nothing and,
 * worse, an "assert nothing was published" that passes for the wrong reason. Real deployments
 * provision topics for the same class of reason; the library's own docs say the same about a
 * consumer's {@code .DLT}.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestKafkaTopics {

  @Bean
  NewTopic orderingEvents(@Value("${ordering.events-topic}") String topic) {
    return TopicBuilder.name(topic).partitions(1).replicas(1).build();
  }
}
