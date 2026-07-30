package com.aipersimmon.ddd.processmanager.engine.autoconfigure;

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
 * Which databases the process manager claims to run on.
 *
 * <p>The interesting assertions here are the <strong>refusals</strong>, which is exactly what no
 * other test can see: a product this resolver accepts silently gets a dialect, and whether that
 * dialect's SQL actually parses on it is discovered in production, one poll at a time.
 *
 * <p>MariaDB is the case that motivated the test. It used to be accepted as a MySQL alias on the
 * assumption that the two are compatible — true of the DDL, false of {@code FOR UPDATE SKIP
 * LOCKED}, which is the one statement this resolution decides (MySQL 8.0; MariaDB not until 10.6).
 * Nothing in this repository has ever tested MariaDB, so the alias was a claim of support nobody
 * had made.
 */
class ProcessVendorsTest {

  private static final String PROPERTY = "aipersimmon.ddd.process-manager.dialect";

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
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "getMetaData" -> metaData;
                    case "close" -> null;
                    default -> null;
                  });
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

  private static String probe(String product) {
    return ProcessVendors.probe(new ProductNameOnly(product), PROPERTY);
  }

  @Test
  void theThreeTestedDatabasesResolveToTheirOwnDialect() {
    assertEquals("postgresql", probe("PostgreSQL"));
    assertEquals("mysql", probe("MySQL"));
    assertEquals("h2", probe("H2"));
  }

  @Test
  void mariaDbIsRefusedRatherThanTreatedAsMysql() {
    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> probe("MariaDB"));

    // It was accepted as a MySQL alias for a long time. The compatibility that alias assumed holds
    // for the DDL and fails for the one statement this choice decides: SKIP LOCKED is MySQL 8.0
    // and MariaDB 10.6, so an older server answered every claim with a syntax error, delivered no
    // effect, and did not fail loudly enough for anyone to look.
    assertTrue(refused.getMessage().contains("MariaDB".toLowerCase()), refused.getMessage());
    assertTrue(
        refused.getMessage().contains("h2, postgresql, mysql"),
        "the message must name what IS supported, or the reader has nowhere to go");
    assertTrue(
        refused.getMessage().contains(PROPERTY),
        "and the escape hatch, because refusing is not the same as forbidding");
  }

  @Test
  void anUnknownDatabaseIsRefusedRatherThanGuessed() {
    // The alternative is picking a dialect by resemblance, which is how MariaDB got here.
    assertThrows(IllegalStateException.class, () -> probe("Oracle"));
    assertThrows(IllegalStateException.class, () -> probe("Microsoft SQL Server"));
  }

  @Test
  void theProductNameIsMatchedCaseInsensitivelyAndAsASubstring() {
    // Drivers decorate the product name; PostgreSQL's JDBC driver has reported plain "PostgreSQL"
    // for years, but the H2 and MySQL ones have carried suffixes.
    assertEquals("postgresql", probe("PostgreSQL 16.2 (Debian)"));
    assertEquals("mysql", probe("MYSQL COMMUNITY SERVER"));
  }
}
