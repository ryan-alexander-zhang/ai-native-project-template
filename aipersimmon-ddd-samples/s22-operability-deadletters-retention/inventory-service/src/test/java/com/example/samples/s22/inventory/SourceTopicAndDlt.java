package com.example.samples.s22.inventory;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The complete deployment: the source topic and its {@code .DLT}, provisioned together.
 *
 * <p>Which is the actual lesson of this module and it fits on one line: <strong>a consumer's topic list
 * is not one topic per subscription, it is two.</strong> The library deliberately neither auto-creates the
 * dead-letter topic nor probes for it — a probe would false-fail every auto-creating environment and could
 * only warn in the rest — so provisioning it is the deployment's job, and knowing that is the deployment's
 * job is this sample's.
 */
@TestConfiguration(proxyBeanMethods = false)
class SourceTopicAndDlt {

  @Bean
  NewTopic orderingEvents(@Value("${inventory.ordering-events-topic}") String topic) {
    return TopicBuilder.name(topic).partitions(1).replicas(1).build();
  }

  @Bean
  NewTopic orderingEventsDeadLetters(@Value("${inventory.ordering-events-topic}") String topic) {
    return TopicBuilder.name(topic + ".DLT").partitions(1).replicas(1).build();
  }
}
