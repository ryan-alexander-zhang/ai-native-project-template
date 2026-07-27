package com.aipersimmon.ddd.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Spring Boot Testcontainers config for a Kafka broker. Import it from a {@code @SpringBootTest}
 * and Spring Boot derives {@code spring.kafka.bootstrap-servers} from the container and manages its
 * lifecycle (shared across test classes via context caching). The typed container needs no {@code
 * name} hint.
 *
 * <pre>{@code
 * @SpringBootTest
 * @Import({PostgresServiceConnection.class, KafkaServiceConnection.class})
 * class OrderFlowTest { ... }
 * }</pre>
 *
 * <p>A real broker, not an embedded one: the point of an integration test over this transport is
 * the broker hop itself — an integration event written to the outbox, relayed to a topic, consumed
 * back through the inbox-guarded bridge. An embedded broker is the cheaper way to test the
 * library's own producer and consumer wiring, and that is what the library uses internally, but it
 * is not what a consumer should trust to prove reliable delivery in their own application.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaServiceConnection {

  @Bean
  @ServiceConnection
  KafkaContainer kafkaContainer() {
    return new KafkaContainer(DockerImageName.parse(ContainerImages.KAFKA));
  }
}
