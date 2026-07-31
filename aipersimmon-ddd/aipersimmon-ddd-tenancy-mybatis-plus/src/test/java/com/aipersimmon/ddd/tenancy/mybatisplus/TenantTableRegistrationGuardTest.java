package com.aipersimmon.ddd.tenancy.mybatisplus;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The allow-list's failure direction is "open": a consumer table carrying the tenant column but
 * missing from {@code tenant-tables} gets no {@code tenant_id = ?} predicate, and every tenant sees
 * every tenant's rows — with no symptom. This guard turns that omission into a startup failure:
 * every base table with the tenant column must be either registered (interceptor-scoped) or
 * explicitly exempted (the consumer scopes it by hand), so each table's tenancy is a decision on
 * record rather than a memory test. The framework's own {@code aipersimmon_*} tables are exempt by
 * design — relays scan them tenant-lessly and each row carries its tenant as a stamped data column.
 */
class TenantTableRegistrationGuardTest {

  private static final DataSource DB = h2("guard_test");

  private static DataSource h2(String name) {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
    return ds;
  }

  @BeforeAll
  static void schema() throws SQLException {
    try (Connection connection = DB.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE IF NOT EXISTS customers (id VARCHAR(36) PRIMARY KEY,"
              + " tenant_id VARCHAR(64) NOT NULL)");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS payment_operations (operation_id VARCHAR(64),"
              + " tenant_id VARCHAR(64) NOT NULL, PRIMARY KEY (tenant_id, operation_id))");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS aipersimmon_outbox (event_id VARCHAR(36) PRIMARY KEY,"
              + " tenant_id VARCHAR(64) NOT NULL)");
      statement.execute("CREATE TABLE IF NOT EXISTS shedlock (name VARCHAR(64) PRIMARY KEY)");
      // A view over a tenant table must not be flagged: the interceptor rewrites base-table
      // statements, and the view's base table is already accounted for.
      statement.execute("CREATE OR REPLACE VIEW customers_view AS SELECT * FROM customers");
    }
  }

  @Test
  void passesWhenEveryTenantTableIsRegisteredOrExempted() {
    TenantTableRegistrationGuard guard =
        new TenantTableRegistrationGuard(
            DB, "tenant_id", List.of("customers"), List.of("payment_operations"));
    assertThatCode(guard::verify).doesNotThrowAnyException();
  }

  @Test
  void failsNamingEveryUndecidedTableAndOnlyThose() {
    TenantTableRegistrationGuard guard =
        new TenantTableRegistrationGuard(DB, "tenant_id", List.of(), List.of());
    assertThatThrownBy(guard::verify)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("customers")
        .hasMessageContaining("payment_operations")
        // The framework's own tables are tenant-less by design, never a consumer's omission.
        .satisfies(
            thrown -> {
              org.assertj.core.api.Assertions.assertThat(thrown.getMessage())
                  .doesNotContain("aipersimmon_outbox")
                  .doesNotContain("shedlock")
                  .doesNotContain("customers_view");
            });
  }

  @Test
  void aSchemaQualifiedRegistrationOrExemptionCounts() {
    TenantTableRegistrationGuard guard =
        new TenantTableRegistrationGuard(
            DB, "tenant_id", List.of("public.customers"), List.of("public.payment_operations"));
    assertThatCode(guard::verify).doesNotThrowAnyException();
  }
}
