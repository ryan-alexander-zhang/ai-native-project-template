package com.example;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.example.inventory.api.StockReservationFailed;
import com.example.ordering.application.order.FindOrder;
import com.example.ordering.application.order.OrderSnapshot;
import com.example.ordering.application.order.PlaceOrder;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * outcome as an integration event; the process manager then sends a {@code ConfirmOrder} or
 * {@code CancelOrder} command. Reads go through the query bus.
 *
 * <p>Unlike the earlier in-process version, the cross-context cascade now rides the real transport:
 * each integration event is written to the transactional outbox, relayed to a Kafka topic, consumed
 * back through the inbox-guarded bridge, and republished in process. The flow is therefore fully
 * asynchronous, so the tests {@code await} the terminal state rather than pumping a relay by hand.
 *
 * <p>The failure cases also assert the stable {@link com.aipersimmon.ddd.core.error.ErrorCode}
 * rides the {@link StockReservationFailed} event — inventory has no HTTP surface, so this is how its
 * coded domain errors surface a machine identity to the reacting process.
 */
@SpringBootTest(properties = {
        "aipersimmon.ddd.process-manager.jdbc.effect-relay.poll-delay=200ms",
        "aipersimmon.ddd.process-manager.jdbc.deadline-worker.poll-delay=1h",
        "aipersimmon.ddd.outbox.poll-delay-ms=200",
})
@Import(TestInfrastructure.class)
class OrderingFlowTest {

    private static final Duration SETTLE = Duration.ofSeconds(30);

    @Autowired
    CommandBus commandBus;

    @Autowired
    QueryBus queryBus;

    @Autowired
    StockReservationFailedRecorder failures;

    @BeforeEach
    void clearRecorder() {
        failures.clear();
    }

    private String status(String orderId) {
        return queryBus.ask(new FindOrder(orderId)).orElseThrow().status();
    }

    @Test
    void placingAnOrderReservesStockAndTheProcessConfirmsTheOrder() {
        String orderId = commandBus.send(new PlaceOrder(
                "CUST-1",
                List.of(new PlaceOrder.Line("SKU-1", 2, 100, "USD"))));

        await().atMost(SETTLE).untilAsserted(() -> assertEquals("CONFIRMED", status(orderId)));
    }

    @Test
    void whenSkuIsUnknownTheOrderIsRejectedSynchronouslyByTheInventoryGateway() {
        // SKU-404 is not carried by inventory. The synchronous availability gateway
        // (ordering's anti-corruption layer over inventory's StockAvailabilityApi) fails
        // the order fast, at place time — it is never created and never reaches the process.
        DomainException rejected = assertThrows(DomainException.class, () ->
                commandBus.send(new PlaceOrder(
                        "CUST-1",
                        List.of(new PlaceOrder.Line("SKU-404", 1, 100, "USD")))));

        assertEquals("ordering.stock-unavailable",
                rejected.errorCode().map(ErrorCode::code).orElse(null));
        assertNull(failures.last(), "no reservation should have been attempted for a rejected order");
    }

    @Test
    void whenStockIsInsufficientTheSagaCancelsWithInsufficientStockCode() {
        // SKU-1 is carried and in stock, so it passes the synchronous availability gate;
        // the exact-quantity check is the reservation's job. SKU-1 has 10 units, so asking
        // for 999 is reserved asynchronously, fails, and the process compensates by cancelling.
        String orderId = commandBus.send(new PlaceOrder(
                "CUST-1",
                List.of(new PlaceOrder.Line("SKU-1", 999, 1, "USD"))));

        await().atMost(SETTLE).untilAsserted(() -> assertEquals("CANCELLED", status(orderId)));
        await().atMost(SETTLE).untilAsserted(() ->
                assertEquals("inventory.insufficient-stock", failures.last().code()));
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
