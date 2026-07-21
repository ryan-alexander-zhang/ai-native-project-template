package com.aipersimmon.ddd.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;

/**
 * Spring Boot Testcontainers config for MySQL. Import it from a {@code @SpringBootTest} and Spring
 * Boot derives {@code spring.datasource.*} from the container and manages its lifecycle (shared
 * across test classes via context caching). The typed container needs no {@code name} hint.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlServiceConnection {

  @Bean
  @ServiceConnection
  MySQLContainer<?> mysqlContainer() {
    return new MySQLContainer<>(ContainerImages.MYSQL);
  }
}
