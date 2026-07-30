package com.aipersimmon.ddd.processmanager.engine.autoconfigure;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;

/**
 * Resolves which SQL dialect the process manager's claim statements should use, from the
 * DataSource's reported product name.
 *
 * <p>It lives in the engine because both storage backends had an identical copy, and the one thing
 * this resolution must not do is drift between them: it decides whether the claim uses {@code FOR
 * UPDATE SKIP LOCKED}, and a backend that answers differently from its sibling would deliver
 * effects on one and fail every poll on the other.
 *
 * <p><strong>MariaDB is deliberately not recognised.</strong> It was accepted here as a MySQL alias
 * on the assumption that the two are compatible — which holds for the DDL and does not hold for
 * exactly the statement this choice decides: {@code SKIP LOCKED} arrived in MySQL 8.0 but not until
 * MariaDB 10.6, so an older server got a syntax error on every poll, delivered no effect, and
 * failed too quietly to notice. There is no MariaDB migration, test or container anywhere in this
 * repository, so it was never a supported database, and the honest place to say so is startup. An
 * application that wants to try it sets the dialect property explicitly and knows it is on an
 * untested path.
 */
public final class ProcessVendors {

  private ProcessVendors() {}

  /**
   * The vendor id for {@code dataSource}.
   *
   * @param dialectProperty the property name to name in the failure message, which differs per
   *     backend
   * @throws IllegalStateException if the product cannot be read, or is not one of h2, postgresql,
   *     mysql
   */
  public static String probe(DataSource dataSource, String dialectProperty) {
    String product;
    try (Connection connection = dataSource.getConnection()) {
      product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    } catch (SQLException e) {
      throw new IllegalStateException(
          "cannot probe the database product for dialect auto-selection", e);
    }
    if (product.contains("postgresql")) {
      return "postgresql";
    }
    if (product.contains("mysql")) {
      return "mysql";
    }
    if (product.contains("h2")) {
      return "h2";
    }
    throw new IllegalStateException(
        "cannot auto-select a process-manager dialect for database '"
            + product
            + "'; supported vendors are h2, postgresql, mysql. Set "
            + dialectProperty
            + " explicitly to run on anything else, and note that only those three are tested.");
  }
}
