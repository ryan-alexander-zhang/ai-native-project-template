package com.example.samples.s01;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.example.samples.s01.audit.CurrentActor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One context, one PostgreSQL, and the audit table read directly.
 *
 * <p>Read with SQL rather than through {@code OperationLogReader}, on purpose: what these tests are about
 * is which row was written and what is in its columns, and going through the read port would make the
 * assertions depend on the port's own paging and criteria behaviour. The columns are the contract an
 * auditor's tooling will be written against.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
abstract class AuditTestBase {

  @Autowired protected TestRestTemplate http;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected CommandBus commandBus;

  @BeforeEach
  void emptyTheTables() {
    jdbc.update("DELETE FROM aipersimmon_operation_log");
    jdbc.update("DELETE FROM s01_order_line");
    jdbc.update("DELETE FROM s01_order");
    // A binding left behind by a previous test is exactly the failure one of these tests is about, so
    // no test may inherit one by accident.
    CurrentActor.clear();
  }

  protected List<Map<String, Object>> auditRows() {
    return jdbc.queryForList(
        "SELECT * FROM aipersimmon_operation_log ORDER BY recorded_at, record_id");
  }

  protected Map<String, Object> onlyAuditRow() {
    List<Map<String, Object>> rows = auditRows();
    if (rows.size() != 1) {
      throw new AssertionError("expected exactly one audit row, found " + rows.size() + ": " + rows);
    }
    return rows.get(0);
  }

  protected long auditRowCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_operation_log", Long.class);
  }

  protected long orderCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM s01_order", Long.class);
  }
}
