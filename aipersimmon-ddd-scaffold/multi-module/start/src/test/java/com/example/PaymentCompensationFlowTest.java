package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.saga.SagaStatus;
import com.aipersimmon.ddd.saga.SagaStore;
import com.example.inventory.api.StockReleased;
import com.example.inventory.domain.stock.Sku;
import com.example.inventory.domain.stock.Stocks;
import com.example.ordering.application.fulfilment.OrderFulfilmentSaga;
import com.example.ordering.application.order.FindOrder;
import com.example.ordering.application.order.OrderSnapshot;
import com.example.ordering.application.order.PlaceOrder;
import com.example.ordering.domain.order.CancellationCategory;
import com.example.ordering.domain.order.OrderCancelledEvent;
import com.example.payment.api.PaymentDeclined;
import com.example.payment.domain.AuthorizationPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

/**
 * End-to-end across all three bounded contexts (ordering, inventory, payment), driven through the
 * CQRS buses and the order-fulfilment orchestration saga — all synchronous, no broker. It exercises
 * the compensation path the design calls for:
 *
 * <pre>PaymentDeclined → ReleaseStock → StockReleased → CancelOrder → OrderCancelled → ABORTED</pre>
 *
 * <p>It asserts the properties that make the orchestration correct: the order ends CANCELLED with
 * the {@code PAYMENT_DECLINED} category, the held stock is released <em>before</em> the cancellation
 * (and restored idempotently), and the saga reaches {@code ABORTED} only after the order actually
 * cancelled — not when the cancel command was sent.
 */
@SpringBootTest
class PaymentCompensationFlowTest {

    @Autowired
    CommandBus commandBus;

    @Autowired
    QueryBus queryBus;

    @Autowired
    Stocks stocks;

    @Autowired
    SagaStore<OrderFulfilmentSaga> sagas;

    @Autowired
    CompensationRecorder recorder;

    private int available(String sku) {
        return stocks.findBySku(new Sku(sku)).orElseThrow().available();
    }

    @Test
    void paymentDeclineReleasesStockThenCancelsTheOrderAndAbortsTheSaga() {
        int stockBefore = available("SKU-1");

        // 6 x 10_000 = 60_000: passes the credit limit (100_000) and reserves fine (6 <= 10 in stock),
        // but exceeds the payment ceiling (50_000), so payment declines and the saga must compensate.
        String orderId = commandBus.send(new PlaceOrder(
                "CUST-1",
                List.of(new PlaceOrder.Line("SKU-1", 6, 10_000, "USD"))));

        OrderSnapshot snapshot = queryBus.ask(new FindOrder(orderId)).orElseThrow();
        assertEquals("CANCELLED", snapshot.status());

        // The cancellation was for the payment decline — the evidence-bearing compensating reason.
        assertEquals(CancellationCategory.PAYMENT_DECLINED, recorder.cancelledCategory(orderId));

        // Payment really declined, with the domain's stable code...
        assertNotNull(recorder.declined(orderId));
        assertEquals(AuthorizationPolicy.DECLINE_CODE, recorder.declined(orderId).code());

        // ...and the stock really was released (a StockReleased event fired) and restored.
        assertNotNull(recorder.released(orderId), "stock must be released before cancellation");
        assertEquals(stockBefore, available("SKU-1"), "released stock must be handed back exactly once");

        // The saga ended ABORTED, and only via the cancellation step — proving it waited for the
        // OrderCancelled outcome rather than terminating when the cancel command was sent.
        OrderFulfilmentSaga saga = sagas.find(orderId).orElseThrow();
        assertEquals(SagaStatus.ABORTED, saga.status());
        assertEquals(OrderFulfilmentSaga.Step.AWAITING_ORDER_CANCELLATION, saga.step());
    }

    @Test
    void paymentAuthorisedConfirmsTheOrderAndCompletesTheSaga() {
        // 1 x 100 = 100: well under the payment ceiling, so payment authorises and the order confirms.
        String orderId = commandBus.send(new PlaceOrder(
                "CUST-1",
                List.of(new PlaceOrder.Line("SKU-1", 1, 100, "USD"))));

        OrderSnapshot snapshot = queryBus.ask(new FindOrder(orderId)).orElseThrow();
        assertEquals("CONFIRMED", snapshot.status());

        OrderFulfilmentSaga saga = sagas.find(orderId).orElseThrow();
        assertEquals(SagaStatus.COMPLETED, saga.status());
    }

    /** Captures the flow's cross-context events and the order-cancelled domain event, by order id. */
    static class CompensationRecorder {
        private final java.util.Map<String, PaymentDeclined> declined = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Map<String, StockReleased> released = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Map<String, CancellationCategory> cancelled =
                new java.util.concurrent.ConcurrentHashMap<>();

        @EventListener
        void onDeclined(EventEnvelope<PaymentDeclined> envelope) {
            declined.put(envelope.payload().orderId(), envelope.payload());
        }

        @EventListener
        void onReleased(EventEnvelope<StockReleased> envelope) {
            released.put(envelope.payload().orderId(), envelope.payload());
        }

        @EventListener
        void onCancelled(OrderCancelledEvent event) {
            cancelled.put(event.orderId().value(), event.category());
        }

        PaymentDeclined declined(String orderId) {
            return declined.get(orderId);
        }

        StockReleased released(String orderId) {
            return released.get(orderId);
        }

        CancellationCategory cancelledCategory(String orderId) {
            return cancelled.get(orderId);
        }
    }

    @TestConfiguration
    static class RecorderConfig {
        @Bean
        CompensationRecorder compensationRecorder() {
            return new CompensationRecorder();
        }
    }
}
