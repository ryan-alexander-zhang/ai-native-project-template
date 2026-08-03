package com.example;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.inventory.api.StockReservationFailed;
import com.example.ordering.application.order.FindOrder;
import com.example.ordering.application.order.PlaceOrder;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

/**
 * End-to-end across all bounded contexts, driven through the CQRS buses and the durable
 * order-fulfilment process manager. Sending a {@code PlaceOrder} command starts the flow and
 * announces the order; inventory reacts (via its own {@code ReserveStock} command) and reports the
 * outcome as an integration event; the process manager then sends a {@code ConfirmOrder} or {@code
 * CancelOrder} command. Reads go through the query bus.
 *
 * <p>Unlike the earlier in-process version, the cross-context cascade now rides the real transport:
 * each integration event is written to the transactional outbox, relayed to a Kafka topic, consumed
 * back through the inbox-guarded bridge, and republished in process. The flow is therefore fully
 * asynchronous, so the tests {@code await} the terminal state rather than pumping a relay by hand.
 *
 * <p>The failure cases also assert the stable {@link com.aipersimmon.ddd.core.error.ErrorCode}
 * rides the {@link StockReservationFailed} event — inventory has no HTTP surface, so this is how
 * its coded domain errors surface a machine identity to the reacting process.
 */
@SpringBootTest(
    properties = {
      "aipersimmon.ddd.process-manager.effect-relay.poll-delay=200ms",
      "aipersimmon.ddd.process-manager.deadline-worker.enabled=false",
      "aipersimmon.ddd.outbox.poll-delay-ms=200",
    })
@Import(TestInfrastructure.class)
@ExtendWith(BoundTenant.class)
class OrderingFlowTest {

  private static final Duration SETTLE = Duration.ofSeconds(30);

  @Autowired CommandBus commandBus;

  @Autowired QueryBus queryBus;

  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

  @Autowired StockReservationFailedRecorder failures;

  @BeforeEach
  void clearRecorder() {
    failures.clear();
  }

  private String status(String orderId) {
    return queryBus.ask(new FindOrder(orderId)).orElseThrow().status().name();
  }

  @Test
  void placingAnOrderReservesStockAndTheProcessConfirmsTheOrder() {
    String orderId =
        commandBus.send(
            new PlaceOrder("CUST-1", List.of(new PlaceOrder.Line("SKU-1", 2, 100, "USD"))));

    await().atMost(SETTLE).untilAsserted(() -> assertEquals("CONFIRMED", status(orderId)));
  }

  @Test
  void aZeroAmountOrderIsConfirmedRatherThanQuietlyCancelledTwoMinutesLater() {
    // The cross-context amount range, end to end. Ordering accepts a zero-amount
    // line; payment used to reject the resulting authorization as a constraint violation, which
    // is not a rejection anyone sees — the command never reached its handler and no outcome event
    // was ever published, so the process parked on its AWAITING_PAYMENT step and the order sat at
    // FULFILMENT_IN_PROGRESS (which is what this test observes with the deadline worker off). In
    // production the payment deadline then cancelled it as PAYMENT_TIMEOUT, a reason unrelated to
    // the truth. Either way the symptom is nowhere near the cause.
    String orderId =
        commandBus.send(
            new PlaceOrder("CUST-1", List.of(new PlaceOrder.Line("SKU-1", 1, 0, "USD"))));

    await().atMost(SETTLE).untilAsserted(() -> assertEquals("CONFIRMED", status(orderId)));
  }

  @Test
  void theFulfilmentProcessJoinsThePlacingCommandsCausalChain() {
    // One business flow, one correlation: the process instance the PlaceOrder
    // triggers must carry the PlaceOrder's correlationId, not a fresh chain minted at the
    // domain-event hop. Dispatching via sendAs pins the command's identity so the assertion
    // has something known to compare against.
    CommandContext placing =
        CommandContext.root(Tenants.of(BoundTenant.TENANT), "place-" + UUID.randomUUID());

    String orderId =
        commandBus.sendAs(
            new PlaceOrder("CUST-1", List.of(new PlaceOrder.Line("SKU-1", 1, 100, "USD"))),
            placing);

    await().atMost(SETTLE).untilAsserted(() -> assertEquals("CONFIRMED", status(orderId)));
    String startCorrelation =
        jdbc.queryForObject(
            "SELECT correlation_id FROM aipersimmon_process_transition WHERE input_message_id = ?",
            String.class,
            "ready-for-fulfilment:" + orderId);
    assertEquals(
        placing.correlationId(),
        startCorrelation,
        "the process's start transition must sit on the placing command's causal chain,"
            + " not on a chain of its own");
  }

  @Test
  void whenSkuIsUnknownTheOrderIsRejectedSynchronouslyByTheInventoryGateway() {
    // SKU-404 is not carried by inventory. The synchronous availability gateway
    // (ordering's anti-corruption layer over inventory's StockAvailabilityApi) fails
    // the order fast, at place time — it is never created and never reaches the process.
    DomainException rejected =
        assertThrows(
            DomainException.class,
            () ->
                commandBus.send(
                    new PlaceOrder(
                        "CUST-1", List.of(new PlaceOrder.Line("SKU-404", 1, 100, "USD")))));

    assertEquals(
        "ordering.stock-unavailable", rejected.errorCode().map(ErrorCode::code).orElse(null));
    assertNull(failures.last(), "no reservation should have been attempted for a rejected order");
  }

  @Test
  void whenStockVanishesAfterTheGateTheProcessManagerCancelsWithInsufficientStockCode() {
    // The synchronous gate checks quantities now, so an order it can see is hopeless
    // is refused before anything is created — the shape this test used to rely on (999 against a
    // stock of 10 slipping through a SKU-only gate) no longer exists. What the gate still cannot
    // see is the future: it is advisory and holds nothing, so stock that disappears between its
    // answer and the reservation is the reservation's problem. This manufactures exactly that
    // window: the raw write lands milliseconds after placement, the asynchronous reservation only
    // starts at the outbox relay's next poll.
    jdbc.update(
        "INSERT INTO inventory.stocks (sku, available, tenant_id) VALUES ('SKU-VANISH', 6, ?)"
            + " ON CONFLICT (tenant_id, sku) DO UPDATE SET available = 6",
        BoundTenant.TENANT);

    String orderId =
        commandBus.send(
            new PlaceOrder("CUST-1", List.of(new PlaceOrder.Line("SKU-VANISH", 6, 1, "USD"))));
    jdbc.update(
        "UPDATE inventory.stocks SET available = 0 WHERE tenant_id = ? AND sku = 'SKU-VANISH'",
        BoundTenant.TENANT);

    await().atMost(SETTLE).untilAsserted(() -> assertEquals("CANCELLED", status(orderId)));
    await()
        .atMost(SETTLE)
        .untilAsserted(() -> assertEquals("inventory.insufficient-stock", failures.last().code()));
  }

  /** Captures the last {@link StockReservationFailed} so a test can assert its code. */
  static class StockReservationFailedRecorder {
    private volatile StockReservationFailed last;

    @EventListener
    void on(EventEnvelope<StockReservationFailed> envelope) {
      this.last = envelope.payload();
    }

    StockReservationFailed last() {
      return last;
    }

    void clear() {
      this.last = null;
    }
  }

  @TestConfiguration
  static class RecorderConfig {
    @Bean
    StockReservationFailedRecorder stockReservationFailedRecorder() {
      return new StockReservationFailedRecorder();
    }
  }
}
