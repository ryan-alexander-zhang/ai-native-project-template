package com.example;

import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real middleware for the full-context acceptance tests. Imported by every {@code @SpringBootTest}
 * in this module; Spring Boot derives the DataSource and {@code spring.kafka.bootstrap-servers}
 * from the containers and manages their lifecycle, so classes sharing a configuration share one
 * pair of containers through the context cache.
 *
 * <p>Kafka is real on purpose: these tests exercise the broker hop end to end — an integration
 * event is written to the outbox, relayed to the topic, consumed back through the inbox-guarded
 * bridge and republished in process — so the assertions prove the reliable transport, not just the
 * in-process cascade.
 *
 * <h2>Half of this comes from the library, half does not</h2>
 *
 * <p>PostgreSQL is {@link PostgresServiceConnection} from {@code aipersimmon-ddd-test-support},
 * which is what {@code CHOOSING-MODULES.md} points a consumer at, and which also owns the image pin
 * so the sample cannot drift from the version the library tests against.
 *
 * <p>The Kafka container is declared here because the module has no equivalent for it — no {@code
 * KafkaServiceConnection}, and no Kafka entry in its pinned images — even though cross-service
 * integration events over Kafka are the transport the library ships (issue-00067). So the version
 * pin below is the sample's own to keep in step with {@code compose.yaml}, which is precisely the
 * drift the test-support module exists to prevent.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(PostgresServiceConnection.class)
class TestInfrastructure {

  /**
   * Matches the compose broker version (3.7.1). The image name differs because Testcontainers'
   * {@code KafkaContainer} supports the apache/kafka image rather than the bitnami one compose
   * uses; the wire protocol is identical.
   */
  @Bean
  @ServiceConnection
  KafkaContainer kafka() {
    return new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.1"));
  }
}
