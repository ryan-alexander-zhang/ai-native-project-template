package com.aipersimmon.ddd.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Spring Boot Testcontainers config for Redis. Import it from a {@code @SpringBootTest}:
 *
 * <pre>{@code
 * @SpringBootTest
 * @Import(RedisServiceConnection.class)
 * @EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
 * class MyRedisTest { ... }
 * }</pre>
 *
 * <p>Spring Boot cannot infer the service from a bare {@link GenericContainer}, so the
 * {@code @ServiceConnection(name = "redis")} hint is required; Spring then contributes {@code
 * spring.data.redis.*} connection details and manages the container lifecycle (shared across test
 * classes via application-context caching).
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisServiceConnection {

  @Bean
  @ServiceConnection(name = "redis")
  GenericContainer<?> redisContainer() {
    return new GenericContainer<>(DockerImageName.parse(ContainerImages.REDIS))
        .withExposedPorts(6379);
  }
}
