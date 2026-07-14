package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.example.inventory.api.StockReservationFailed;
import com.example.ordering.application.order.FindOrder;
import com.example.ordering.application.order.OrderSnapshot;
import com.example.ordering.application.order.PlaceOrder;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

/**
 * End-to-end across both bounded contexts, driven through the CQRS buses and the
 * order-fulfilment orchestration saga. Sending a {@code PlaceOrder} command starts
 * the saga and announces the order; inventory reacts (via its own {@code ReserveStock}
 * command) and reports the outcome as an integration event; the saga then sends a
 * {@code ConfirmOrder} or {@code CancelOrder} command. Reads go through the query
 * bus. All synchronous, no broker.
 *
 * <p>The failure cases also assert the stable {@link com.aipersimmon.ddd.core.error.ErrorCode}
 * rides the {@link StockReservationFailed} event — inventory has no HTTP surface, so this
 * is how its coded domain errors surface a machine identity to the reacting saga.
 */
@SpringBootTest
class OrderingFlowTest {

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

    @Test
    void placingAnOrderReservesStockAndTheSagaConfirmsTheOrder() {
        String orderId = commandBus.send(new PlaceOrder(
                "CUST-1",
                List.of(new PlaceOrder.Line("SKU-1", 2, 100, "USD"))));

        OrderSnapshot snapshot = queryBus.ask(new FindOrder(orderId)).orElseThrow();
        assertEquals("CONFIRMED", snapshot.status());
    }

    @Test
    void whenSkuIsUnknownTheSagaCancelsWithStockNotFoundCode() {
        String orderId = commandBus.send(new PlaceOrder(
                "CUST-1",
                List.of(new PlaceOrder.Line("SKU-404", 1, 100, "USD"))));

        OrderSnapshot snapshot = queryBus.ask(new FindOrder(orderId)).orElseThrow();
        assertEquals("CANCELLED", snapshot.status());
        assertNotNull(failures.last(), "a StockReservationFailed event should have been published");
        assertEquals("inventory.stock-not-found", failures.last().code());
    }

    @Test
    void whenStockIsInsufficientTheSagaCancelsWithInsufficientStockCode() {
        // SKU-1 is seeded with 10 units; asking for 999 exceeds it.
        String orderId = commandBus.send(new PlaceOrder(
                "CUST-1",
                List.of(new PlaceOrder.Line("SKU-1", 999, 1, "USD"))));

        OrderSnapshot snapshot = queryBus.ask(new FindOrder(orderId)).orElseThrow();
        assertEquals("CANCELLED", snapshot.status());
        assertEquals("inventory.insufficient-stock", failures.last().code());
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
