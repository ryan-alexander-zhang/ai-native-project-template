/**
 * Reusable Testcontainers support for AiPersimmon DDD tests (design-00007).
 *
 * <p>Spring Boot tests import a {@code *ServiceConnection} {@link
 * org.springframework.boot.test.context.TestConfiguration} so connection details are derived
 * automatically and the container lifecycle is Spring-managed. Non-Spring JDBC tests use {@link
 * com.aipersimmon.ddd.testsupport.SharedContainers} (the singleton-container pattern) with {@link
 * com.aipersimmon.ddd.testsupport.TestDataSources}. {@link
 * com.aipersimmon.ddd.testsupport.DockerAvailable} guards tests so a container-less build skips
 * rather than fails.
 */
package com.aipersimmon.ddd.testsupport;
