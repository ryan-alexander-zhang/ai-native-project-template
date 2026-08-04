package com.example.samples.s27;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.example.samples.s27.customer.application.CloseCustomer;
import com.example.samples.s27.customer.application.EraseCustomer;
import com.example.samples.s27.customer.application.RegisterCustomer;
import com.example.samples.s27.customer.domain.CustomerId;
import com.example.samples.s27.customer.domain.Customers;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One context, one PostgreSQL, and every table read with SQL.
 *
 * <p>SQL rather than the ports, because the whole subject is what is left <em>in</em> the rows. A logically
 * deleted row is invisible to the mapper by construction, so asking the repository whether it is still there
 * would always answer no — and the interesting fact is that it is.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
abstract class CustomerTestBase {

  protected static final String ALICE = "cust-alice";
  protected static final String ALICE_EMAIL = "alice@example.com";

  @Autowired protected CommandBus commandBus;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected Customers customers;

  @BeforeEach
  void emptyEverything() {
    jdbc.update("DELETE FROM aipersimmon_operation_log");
    jdbc.update("DELETE FROM aipersimmon_outbox");
    jdbc.update("DELETE FROM aipersimmon_inbox");
    jdbc.update("DELETE FROM s27_marketing_consent");
    jdbc.update("DELETE FROM s27_customer");
  }

  protected void register(String id, String email) {
    commandBus.send(new RegisterCustomer(id, email, "Alice Example", "+44 7700 900000"));
  }

  protected void registerAlice() {
    register(ALICE, ALICE_EMAIL);
  }

  protected void close(String id, String reason) {
    commandBus.send(new CloseCustomer(id, reason));
  }

  protected void erase(String id) {
    commandBus.send(new EraseCustomer(id, "TICKET-42"));
  }

  /** Pretend the relay ran: the erasure gate only cares whether anything is still unsent. */
  protected void drainTheOutbox() {
    jdbc.update("UPDATE aipersimmon_outbox SET sent = TRUE, sent_at = now() WHERE sent = FALSE");
  }

  protected CustomerId id(String value) {
    return new CustomerId(value);
  }

  /** The row as it really is, past the logical-delete filter. */
  protected Map<String, Object> rawRow(String id) {
    List<Map<String, Object>> rows =
        jdbc.queryForList("SELECT * FROM s27_customer WHERE id = ?", id);
    if (rows.size() != 1) {
      throw new AssertionError("expected exactly one raw row for " + id + ", found " + rows.size());
    }
    return rows.get(0);
  }

  protected long rawRowCount(String id) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM s27_customer WHERE id = ?", Long.class, id);
  }

  protected List<Map<String, Object>> outboxRows() {
    return jdbc.queryForList("SELECT * FROM aipersimmon_outbox ORDER BY occurred_at, event_id");
  }

  protected List<Map<String, Object>> auditRows() {
    return jdbc.queryForList(
        "SELECT * FROM aipersimmon_operation_log ORDER BY recorded_at, record_id");
  }

  protected List<Map<String, Object>> auditRowsFor(String operationCode) {
    return jdbc.queryForList(
        "SELECT * FROM aipersimmon_operation_log WHERE operation_code = ?"
            + " ORDER BY recorded_at, record_id",
        operationCode);
  }

  protected long inboxRowCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_inbox", Long.class);
  }
}
