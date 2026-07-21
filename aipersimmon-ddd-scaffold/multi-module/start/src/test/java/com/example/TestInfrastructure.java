package com.example;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real middleware for the full-context acceptance tests via Testcontainers.
 * {@code @ServiceConnection} wires the app's DataSource to the PostgreSQL container and its {@code
 * spring.kafka.bootstrap-servers} to the Kafka container — the same two middleware the app runs
 * against via docker-compose in dev. The aipersimmon Flyway starter creates the process-manager,
 * outbox, and inbox tables on the container at startup. Imported by every {@code @SpringBootTest}
 * in this module.
 *
 * <p>Kafka is real here on purpose: these tests exercise the broker hop end to end — an integration
 * event is written to the outbox, relayed to the topic, consumed back through the inbox-guarded
 * bridge, and republished in process — so the assertions prove the reliable transport, not just the
 * in-process cascade.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestInfrastructure {

  // Versions track the docker-compose middleware so tests and dev run the same engines.
  // Postgres matches compose.yaml (postgres:18.1). Kafka matches the compose broker version
  // (3.7.1); the image name differs because Testcontainers' KafkaContainer supports the
  // apache/kafka image rather than the bitnami one compose uses — the wire protocol is identical.
  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgres() {
    return new PostgreSQLContainer<>("postgres:18.1");
  }

  @Bean
  @ServiceConnection
  KafkaContainer kafka() {
    return new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.1"));
  }
}
