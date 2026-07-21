package com.aipersimmon.ddd.testsupport;

import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The Testcontainers singleton-container pattern for tests that have <em>no</em> Spring context
 * (raw-JDBC tests), where {@code @ServiceConnection} does not apply. Each container is started once
 * per JVM and shared across every test class in the module's Surefire fork, so a module with
 * several JDBC tests pays a single container start instead of one per class. {@code
 * withReuse(true)} additionally reuses the container across builds when {@code
 * testcontainers.reuse.enable=true} is set in {@code ~/.testcontainers.properties}; without that it
 * is a harmless no-op.
 *
 * <p>Spring Boot tests should NOT use these — import a {@code *ServiceConnection} config instead,
 * so the container lifecycle is Spring-managed (see the package docs).
 */
public final class SharedContainers {

  private SharedContainers() {}

  private static volatile PostgreSQLContainer<?> postgres;
  private static volatile MySQLContainer<?> mysql;

  /** Shared PostgreSQL 16 container, started on first use. */
  public static PostgreSQLContainer<?> postgres() {
    PostgreSQLContainer<?> c = postgres;
    if (c == null) {
      synchronized (SharedContainers.class) {
        c = postgres;
        if (c == null) {
          c = new PostgreSQLContainer<>(ContainerImages.POSTGRES).withReuse(true);
          c.start();
          postgres = c;
        }
      }
    }
    return c;
  }

  /** Shared MySQL 8 container, started on first use. */
  public static MySQLContainer<?> mysql() {
    MySQLContainer<?> c = mysql;
    if (c == null) {
      synchronized (SharedContainers.class) {
        c = mysql;
        if (c == null) {
          c = new MySQLContainer<>(ContainerImages.MYSQL).withReuse(true);
          c.start();
          mysql = c;
        }
      }
    }
    return c;
  }
}
