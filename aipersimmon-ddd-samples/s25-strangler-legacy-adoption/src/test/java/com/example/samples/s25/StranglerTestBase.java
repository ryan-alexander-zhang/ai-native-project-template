package com.example.samples.s25;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.example.samples.s25.acl.LegacyRefundEntryPoint;
import com.example.samples.s25.legacy.LegacyOrderService;
import com.example.samples.s25.refunds.domain.Refunds;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One context, one PostgreSQL, the monolith and the new aggregate over the same table.
 *
 * <p>Reads go through {@code JdbcTemplate} rather than through either path, because the whole subject is what is in the
 * row after two different writers have had a turn at it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
abstract class StranglerTestBase {

  @Autowired protected CommandBus commandBus;
  @Autowired protected QueryBus queryBus;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected LegacyOrderService legacy;
  @Autowired protected LegacyRefundEntryPoint entryPoint;
  @Autowired protected Refunds refunds;

  @BeforeEach
  void emptyEverything() {
    jdbc.update("DELETE FROM aipersimmon_outbox");
    jdbc.update("DELETE FROM legacy_refunds");
    jdbc.update("DELETE FROM legacy_order_items");
    jdbc.update("DELETE FROM legacy_orders");
  }

  /** An order the monolith placed, in the monolith's way. */
  protected long placeLegacyOrder(long totalCents) {
    return legacy.placeOrder("cust-1", totalCents);
  }

  protected Map<String, Object> refundRow(long refundId) {
    return jdbc.queryForMap("SELECT * FROM legacy_refunds WHERE id = ?", refundId);
  }

  protected long refundVersion(long refundId) {
    return jdbc.queryForObject(
        "SELECT version FROM legacy_refunds WHERE id = ?", Long.class, refundId);
  }

  protected long outboxRowCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_outbox", Long.class);
  }
}
