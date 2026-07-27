package com.aipersimmon.ddd.testsupport;

/**
 * Single source of truth for the container images used across the test support, so every test pins
 * the same versions. PostgreSQL and Kafka match the reference infrastructure in the multi-module
 * scaffold's {@code start/compose.yaml}; MySQL and Redis are not part of that compose and are
 * pinned to the versions the library's cross-database and web-store tests exercise.
 */
public final class ContainerImages {

  private ContainerImages() {}

  /** Matches {@code postgres} in multi-module {@code start/compose.yaml}. */
  public static final String POSTGRES = "postgres:18.1";

  /** MySQL used by the process-manager cross-database tests (not in the reference compose). */
  public static final String MYSQL = "mysql:8.0";

  /** Redis used by the web-store tests (not in the reference compose). */
  public static final String REDIS = "redis:7-alpine";

  /**
   * Kafka broker for tests that exercise the reliable transport. Same broker version as {@code
   * kafka} in multi-module {@code start/compose.yaml}; the image differs because Testcontainers'
   * {@code KafkaContainer} drives the Apache-published image rather than the Bitnami one compose
   * uses, and the wire protocol is identical.
   */
  public static final String KAFKA = "apache/kafka:3.7.1";
}
