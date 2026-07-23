package com.example;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.example.ordering.application.order.PlaceOrder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the Operation Log records a business-readable row for write commands, end to end against a
 * real PostgreSQL. It asserts two things the wiring must deliver: (1) a command dispatched directly
 * (an HTTP-style {@code PlaceOrder}) is recorded synchronously in the same transaction as {@code
 * SUCCEEDED}/{@code COMMITTED}, stamped with the trusted actor and tenant from the resolvers —
 * never from the payload; and (2) a command the process manager issues later ({@code ConfirmOrder})
 * is recorded too, showing the capture is on the command bus, not tied to any one entry point.
 *
 * <p>A third case proves the failure path: a rejected command records a {@code REJECTED} row whose
 * summary is the rendered {@code failure} template (stable code + safe summary), never the raw
 * exception.
 */
@SpringBootTest(
    properties = {
      "aipersimmon.ddd.process-manager.jdbc.effect-relay.poll-delay=200ms",
      "aipersimmon.ddd.process-manager.jdbc.deadline-worker.poll-delay=1h",
      "aipersimmon.ddd.outbox.poll-delay-ms=200",
    })
@Import(TestInfrastructure.class)
class OperationLogRecordingTest {

  private static final Duration SETTLE = Duration.ofSeconds(30);

  @Autowired CommandBus commandBus;

  @Autowired JdbcTemplate jdbc;

  @Test
  void writeCommandsAreRecordedWithTrustedActorAndTenant() {
    // CUST-1 is seeded; the new order id (the command result) is unique, so it isolates this run's
    // place row from any other test that also orders for CUST-1.
    String orderId =
        commandBus.send(
            new PlaceOrder("CUST-1", List.of(new PlaceOrder.Line("SKU-1", 1, 100, "USD"))));

    // 1) PlaceOrder was recorded synchronously, in the committing transaction, against the customer
    // (the order id does not exist at command time — it is the result, so it rides the summary).
    Map<String, Object> place =
        jdbc.queryForMap(
            "SELECT target_type, target_id, actor_type, actor_id, tenant_id, outcome, completion, summary"
                + " FROM aipersimmon_operation_log"
                + " WHERE operation_code = 'ordering.order.place' AND summary LIKE ?",
            "%" + orderId + "%");
    assertEquals("Customer", place.get("target_type"));
    assertEquals("CUST-1", place.get("target_id"));
    assertEquals("SYSTEM", place.get("actor_type"));
    assertEquals("ordering-scaffold", place.get("actor_id"));
    assertEquals("GLOBAL", place.get("tenant_id"));
    assertEquals("SUCCEEDED", place.get("outcome"));
    assertEquals("COMMITTED", place.get("completion"));

    // 2) The fulfilment process manager later issues ConfirmOrder on the same bus; it is recorded
    // too, targeting the order id. This is what makes it "all write operations", not just inbound.
    await()
        .atMost(SETTLE)
        .untilAsserted(
            () ->
                assertEquals(
                    1,
                    jdbc.queryForObject(
                        "SELECT count(*) FROM aipersimmon_operation_log"
                            + " WHERE operation_code = 'ordering.order.confirm'"
                            + " AND target_id = ? AND outcome = 'SUCCEEDED' AND completion = 'COMMITTED'",
                        Integer.class,
                        orderId)));
  }

  @Test
  void aRejectedCommandRecordsARichFailureRow() {
    // SKU-404 is unknown, so ordering's synchronous availability gateway rejects the command with a
    // DomainException at place time. The failure interceptor records it (in its own transaction).
    assertThrows(
        DomainException.class,
        () ->
            commandBus.send(
                new PlaceOrder("CUST-1", List.of(new PlaceOrder.Line("SKU-404", 1, 100, "USD")))));

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT outcome, failure_code, failure_summary, summary"
                + " FROM aipersimmon_operation_log"
                + " WHERE operation_code = 'ordering.order.place'"
                + " AND outcome = 'REJECTED' AND failure_code = 'ordering.stock-unavailable'"
                + " ORDER BY recorded_at DESC LIMIT 1");
    assertEquals("REJECTED", row.get("outcome"));
    assertEquals("ordering.stock-unavailable", row.get("failure_code"));
    // The rendered `failure` template — the stable code and the safe summary, no raw exception
    // text.
    String summary = (String) row.get("summary");
    assertTrue(
        summary.startsWith("Placing order for customer CUST-1 failed:"),
        () -> "unexpected failure summary: " + summary);
    assertTrue(
        summary.contains("ordering.stock-unavailable"),
        () -> "failure summary should carry the stable code: " + summary);
  }
}
