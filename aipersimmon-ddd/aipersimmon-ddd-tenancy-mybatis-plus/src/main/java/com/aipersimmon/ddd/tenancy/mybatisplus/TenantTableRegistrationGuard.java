package com.aipersimmon.ddd.tenancy.mybatisplus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * Startup check that every base table carrying the tenant column is a <em>decision on record</em>:
 * either registered in {@code tenant-tables} (the interceptor scopes it) or listed in {@code
 * exempt-tables} (the consumer scopes it by hand, as a repository that stamps and filters the
 * column itself does).
 *
 * <p>It exists because the allow-list fails open. The interceptor rewrites only registered tables,
 * so a consumer who adds a tenant-carrying table and forgets to register it gets no {@code
 * tenant_id = ?} predicate on any statement — every tenant reads and writes every tenant's rows,
 * and nothing errors. A column default cannot save the read path. The same reasoning that puts
 * tenant scoping into composite foreign keys ("the application layer can be bypassed; put the
 * constraint where it cannot be") applies to the allow-list itself: its completeness must be
 * checked by the machine, at startup, not remembered by people.
 *
 * <p>Exempt by construction, not by configuration: tables named {@code aipersimmon_*} (the
 * framework's store-and-forward tables — relays scan them tenant-lessly, each row carrying its
 * tenant as a stamped data column) and views (the interceptor rewrites base-table statements; a
 * view's base table is already accounted for).
 */
public final class TenantTableRegistrationGuard {

  /** Catalog/system schemas whose tables are never the consumer's to register. */
  private static final Set<String> SYSTEM_SCHEMAS =
      Set.of("information_schema", "pg_catalog", "mysql", "sys", "performance_schema");

  private static final String FRAMEWORK_TABLE_PREFIX = "aipersimmon_";

  private final DataSource dataSource;
  private final String tenantColumn;
  private final Set<String> registered;
  private final Set<String> exempted;

  public TenantTableRegistrationGuard(
      DataSource dataSource,
      String tenantColumn,
      Collection<String> tenantTables,
      Collection<String> exemptTables) {
    this.dataSource = dataSource;
    this.tenantColumn = tenantColumn.toLowerCase(Locale.ROOT);
    this.registered = normalize(tenantTables);
    this.exempted = normalize(exemptTables);
  }

  private static Set<String> normalize(Collection<String> names) {
    return names.stream()
        .map(name -> name.trim().toLowerCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Scan {@code information_schema} for base tables carrying the tenant column and fail loudly on
   * any that neither list names. Runs after the singletons are up, so migrations have run and the
   * schema under inspection is the one the application will use.
   *
   * @throws IllegalStateException naming every undecided table, or wrapping the {@link
   *     SQLException} if the schema could not be read at all — a guard that cannot look must not
   *     pass
   */
  public void verify() {
    List<String> undecided = new ArrayList<>();
    String sql =
        "SELECT c.table_schema, c.table_name FROM information_schema.columns c"
            + " JOIN information_schema.tables t"
            + " ON c.table_schema = t.table_schema AND c.table_name = t.table_name"
            + " WHERE t.table_type = 'BASE TABLE' AND LOWER(c.column_name) = ?";
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenantColumn);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          String schema = rows.getString(1).toLowerCase(Locale.ROOT);
          String table = rows.getString(2).toLowerCase(Locale.ROOT);
          if (SYSTEM_SCHEMAS.contains(schema) || table.startsWith(FRAMEWORK_TABLE_PREFIX)) {
            continue;
          }
          String qualified = schema + "." + table;
          if (decided(table, qualified)) {
            continue;
          }
          undecided.add(qualified);
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException(
          "could not verify tenant-table registration against information_schema", e);
    }
    if (!undecided.isEmpty()) {
      throw new IllegalStateException(
          "Tables carry the tenant column '"
              + tenantColumn
              + "' but appear in neither aipersimmon.ddd.tenancy.mybatis-plus.tenant-tables nor "
              + ".exempt-tables: "
              + undecided
              + ". Unregistered tables get NO tenant predicate — every tenant sees every tenant's "
              + "rows. Register each table for interceptor scoping, or exempt it to state that "
              + "its repository stamps and filters the column itself.");
    }
  }

  private boolean decided(String table, String qualified) {
    return registered.contains(table)
        || registered.contains(qualified)
        || exempted.contains(table)
        || exempted.contains(qualified);
  }
}
