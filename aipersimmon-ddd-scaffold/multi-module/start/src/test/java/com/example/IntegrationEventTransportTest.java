package com.example;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.application.DurableIntegrationEvents;
import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.ordering.application.order.FindOrder;
import com.example.ordering.application.order.PlaceOrder;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * issue-00044 regression: integration events must actually ride the durable outbox → Kafka → inbox
 * transport, not silently degrade to in-process delivery. Two independent proofs, so a future
 * wiring regression cannot pass unnoticed:
 *
 * <ol>
 *   <li>the active {@link IntegrationEvents} publisher is a {@link DurableIntegrationEvents} — the
 *       transactional-outbox writer claimed the port, not the in-process fallback;
 *   <li>after an order confirms, {@code aipersimmon_inbox} has recorded consumed message keys —
 *       which only happens when the externalized events crossed Kafka and came back through the
 *       inbox-guarded consumer bridge, i.e. the broker hop really occurred.
 * </ol>
 *
 * <p>Before the fix this test would fail on both counts: the in-process publisher shadowed the
 * outbox writer, so nothing was written to the outbox, nothing reached Kafka, and the inbox stayed
 * empty — while the order still confirmed via the in-process cascade.
 */
@SpringBootTest(
    properties = {
      "aipersimmon.ddd.process-manager.jdbc.effect-relay.poll-delay=200ms",
      "aipersimmon.ddd.process-manager.jdbc.deadline-worker.poll-delay=1h",
      "aipersimmon.ddd.outbox.poll-delay-ms=200",
    })
@Import(TestInfrastructure.class)
class IntegrationEventTransportTest {

  private static final Duration SETTLE = Duration.ofSeconds(30);

  @Autowired CommandBus commandBus;
  @Autowired QueryBus queryBus;
  @Autowired IntegrationEvents integrationEvents;
  @Autowired JdbcTemplate jdbc;

  @Test
  void integrationEventsRideTheDurableOutboxAndCrossKafka() {
    // (1) The durable outbox writer must own the port — not the in-process fallback.
    assertInstanceOf(
        DurableIntegrationEvents.class,
        integrationEvents,
        "active IntegrationEvents must be the durable outbox writer, not the in-process fallback");

    String orderId =
        commandBus.send(
            new PlaceOrder("CUST-1", List.of(new PlaceOrder.Line("SKU-1", 1, 100, "USD"))));

    await()
        .atMost(SETTLE)
        .untilAsserted(
            () ->
                assertEquals(
                    "CONFIRMED", queryBus.ask(new FindOrder(orderId)).orElseThrow().status()));

    // (2) Inbox rows exist only if the consumer bridge consumed externalized events off Kafka.
    Long inboxRows = jdbc.queryForObject("select count(*) from aipersimmon_inbox", Long.class);
    assertTrue(
        inboxRows != null && inboxRows > 0,
        "expected inbox rows from the Kafka round-trip, found " + inboxRows);
  }
}
