package com.example;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Provides a real PostgreSQL for the full-context tests via Testcontainers. {@code @ServiceConnection}
 * wires the app's DataSource to the container, and {@code schema.sql} (spring.sql.init) creates the
 * four durable Process Manager tables on it — the same schema the docker-compose Postgres uses when
 * the app is run. Imported by every {@code @SpringBootTest} in this module.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestPostgres {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }
}
