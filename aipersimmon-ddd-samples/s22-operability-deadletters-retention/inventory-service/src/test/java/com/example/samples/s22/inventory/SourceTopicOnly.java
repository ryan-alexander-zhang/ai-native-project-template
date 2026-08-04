package com.example.samples.s22.inventory;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The incomplete deployment: the topic this service consumes exists, and its dead-letter topic does not.
 *
 * <p>This is the normal state of a new consumer. Somebody provisions the topic they can name — the one in
 * the configuration, the one the publisher writes to — and nobody provisions {@code <topic>.DLT}, because
 * its name appears in no configuration file: the error handler derives it. The result is a service that
 * looks completely wired and has no way to give up.
 */
@TestConfiguration(proxyBeanMethods = false)
class SourceTopicOnly {

  @Bean
  NewTopic orderingEvents(@Value("${inventory.ordering-events-topic}") String topic) {
    return TopicBuilder.name(topic).partitions(1).replicas(1).build();
  }
}
