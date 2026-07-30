package com.aipersimmon.ddd.flyway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Which databases the shared migrator will create schemas on.
 *
 * <p>This has to agree with the process manager's dialect resolution, and the reason is the failure
 * that disagreement produces: creating the tables on a database the runtime then refuses to start
 * on is worse than refusing here, because it half-succeeds — the schema exists, the application
 * does not run, and nothing says the two decisions were made by different code. MariaDB used to be
 * accepted here and is now refused, in step with {@code ProcessVendors}.
 *
 * <p>Support is decided once, for every probe, or not at all.
 */
class AipersimmonFlywayVendorTest {

  /** A DataSource that reports a product name and nothing else. */
  private record ProductNameOnly(String product) implements DataSource {

    @Override
    public Connection getConnection() {
      DatabaseMetaData metaData =
          (DatabaseMetaData)
              java.lang.reflect.Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {DatabaseMetaData.class},
                  (proxy, method, args) ->
                      "getDatabaseProductName".equals(method.getName()) ? product : null);
      return (Connection)
          java.lang.reflect.Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> "getMetaData".equals(method.getName()) ? metaData : null);
    }

    @Override
    public Connection getConnection(String username, String password) {
      return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }

  private static String vendorOf(String product) {
    return AipersimmonFlywayMigrator.resolveVendor(new ProductNameOnly(product));
  }

  @Test
  void theThreeShippedVendorsResolveToTheirMigrationDirectory() {
    // These are exactly the directories under aipersimmon/db/migration/<component>/.
    assertEquals("h2", vendorOf("H2"));
    assertEquals("postgresql", vendorOf("PostgreSQL"));
    assertEquals("mysql", vendorOf("MySQL"));
  }

  @Test
  void mariaDbIsRefusedInStepWithTheProcessManagersDialectResolution() {
    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> vendorOf("MariaDB"));

    // It used to be routed to the MySQL migrations — and the message three lines below that branch
    // has always said the supported vendors are h2, postgresql, mysql. The branch and the message
    // disagreed; the message was right.
    assertTrue(refused.getMessage().contains("MariaDB"), refused.getMessage());
    assertTrue(refused.getMessage().contains("h2, postgresql, mysql"), refused.getMessage());
  }

  @Test
  void anUnknownDatabaseIsRefusedAndSaysHowToProceedWithout() {
    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> vendorOf("Oracle"));

    // Refusing to guess must not mean refusing to be used: the message points at the opt-out and
    // the path to apply by hand.
    assertTrue(
        refused.getMessage().contains("aipersimmon.ddd.flyway.enabled=false"),
        refused.getMessage());
  }
}
