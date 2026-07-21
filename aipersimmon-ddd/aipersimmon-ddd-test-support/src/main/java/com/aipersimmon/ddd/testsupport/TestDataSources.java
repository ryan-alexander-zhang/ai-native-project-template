package com.aipersimmon.ddd.testsupport;

import java.sql.Driver;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * Builds a plain {@link DataSource} from a Testcontainers JDBC container, for non-Spring JDBC
 * tests. Loads the container's own driver by reflection, so no JDBC-driver dependency is needed
 * here — the consuming module already has the driver on its test classpath.
 */
public final class TestDataSources {

  private TestDataSources() {}

  /** A {@link DataSource} pointing at {@code container} (url/username/password/driver from it). */
  public static DataSource from(JdbcDatabaseContainer<?> container) {
    try {
      Driver driver =
          (Driver)
              Class.forName(container.getDriverClassName()).getDeclaredConstructor().newInstance();
      return new SimpleDriverDataSource(
          driver, container.getJdbcUrl(), container.getUsername(), container.getPassword());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Cannot load JDBC driver " + container.getDriverClassName(), e);
    }
  }
}
