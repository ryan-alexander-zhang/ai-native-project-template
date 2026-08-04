package com.example.samples.s22.inventory;

import com.aipersimmon.ddd.testsupport.ContainerImages;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A broker with <strong>topic auto-creation off</strong>, which is the only configuration in which this
 * module's central claim is even observable.
 *
 * <p>With auto-creation on — the broker default — a missing {@code <topic>.DLT} is created by the very
 * publish that dead-letters the first bad record. Everything appears to work, and the hazard stays
 * invisible until it reaches the one environment where an administrator turned auto-creation off, which
 * is usually production. This is not a testing trick: it is the difference between a sample that
 * demonstrates the failure and a sample that would have hidden it.
 */
@TestConfiguration(proxyBeanMethods = false)
class StrictKafka {

  @Bean
  @ServiceConnection
  KafkaContainer kafkaContainer() {
    return new KafkaContainer(DockerImageName.parse(ContainerImages.KAFKA))
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
  }
}
