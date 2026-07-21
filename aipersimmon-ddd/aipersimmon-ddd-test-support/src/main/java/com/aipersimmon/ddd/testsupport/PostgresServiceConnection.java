package com.aipersimmon.ddd.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Spring Boot Testcontainers config for PostgreSQL. Import it from a {@code @SpringBootTest} and
 * Spring Boot derives {@code spring.datasource.*} from the container and manages its lifecycle
 * (shared across test classes via context caching). The typed container needs no {@code name} hint.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresServiceConnection {

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgresContainer() {
    return new PostgreSQLContainer<>(ContainerImages.POSTGRES);
  }
}
