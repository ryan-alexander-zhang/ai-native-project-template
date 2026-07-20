package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.processmanager.jdbc.relay.JdbcProcessEffectRelay;
import com.aipersimmon.ddd.processmanager.jdbc.runtime.JdbcProcessQuery;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.runtime.ProcessView;
import com.example.inventory.api.StockReleased;
import com.example.inventory.domain.stock.Sku;
import com.example.inventory.domain.stock.Stocks;
import com.example.ordering.process.fulfilment.OrderFulfilmentDefinition;
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
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

/**
 * End-to-end across all three bounded contexts (ordering, inventory, payment), driven through the
 * CQRS buses and the durable order-fulfilment process manager over a real PostgreSQL. Placing the
 * order stages the flow's first command effect; {@link #settle()} pumps the effect relay, and each
 * dispatch triggers the synchronous cross-context cascade that stages the next — so the whole
 * compensation path runs:
 *
 * <pre>PaymentDeclined → ReleaseStock → StockReleased → CancelOrder → OrderCancelled → COMPLETED</pre>
 *
 * <p>It asserts the order ends CANCELLED with the {@code PAYMENT_DECLINED} category, the held stock is
 * released before cancellation (and restored), and the process instance reaches the COMPLETED
 * lifecycle with the {@code ORDER_CANCELLED} outcome only after the order actually cancelled.
 */
@SpringBootTest(properties = {
        "aipersimmon.ddd.process-manager.jdbc.effect-relay.poll-delay=1h",
        "aipersimmon.ddd.process-manager.jdbc.deadline-worker.poll-delay=1h",
})
@Import(TestPostgres.class)
class PaymentCompensationFlowTest {

    @Autowired
    CommandBus commandBus;
    @Autowired
    QueryBus queryBus;
    @Autowired
    Stocks stocks;
    @Autowired
    JdbcProcessEffectRelay relay;
    @Autowired
    JdbcProcessQuery process;
    @Autowired
    CompensationRecorder recorder;

    private int available(String sku) {
        return stocks.findBySku(new Sku(sku)).orElseThrow().available();
    }

    /** Pump the relay until no effect is dispatched; each dispatch cascades to stage the next. */
    private void settle() {
        for (int i = 0; i < 20 && relay.pollOnce() > 0; i++) {
            // keep draining
        }
    }

    private ProcessView processView(String orderId) {
        return process.findRef(OrderFulfilmentDefinition.PROCESS_TYPE, new ProcessBusinessKey(orderId))
                .flatMap(process::find)
                .orElseThrow();
    }

    @Test
    void paymentDeclineReleasesStockThenCancelsTheOrderAndCompletesCompensation() {
        int stockBefore = available("SKU-1");

        // 6 x 10_000 = 60_000: passes the credit limit (100_000) and reserves fine (6 <= 10 in stock),
        // but exceeds the payment ceiling (50_000), so payment declines and the flow must compensate.
        String orderId = commandBus.send(new PlaceOrder(
                "CUST-1",
                List.of(new PlaceOrder.Line("SKU-1", 6, 10_000, "USD"))));
        settle();

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

        // The instance ended COMPLETED with the ORDER_CANCELLED outcome — the compensated business
        // result — reached only via the cancellation, not when the cancel command was dispatched.
        ProcessView view = processView(orderId);
        assertEquals(ProcessLifecycle.COMPLETED, view.lifecycle());
        assertEquals("ORDER_CANCELLED", view.outcome().orElseThrow().value());
        assertTrue(view.step().value().equals("CANCELLED"));
    }

    @Test
    void paymentAuthorisedConfirmsTheOrderAndCompletesTheFlow() {
        // 1 x 100 = 100: well under the payment ceiling, so payment authorises and the order confirms.
        String orderId = commandBus.send(new PlaceOrder(
                "CUST-1",
                List.of(new PlaceOrder.Line("SKU-1", 1, 100, "USD"))));
        settle();

        OrderSnapshot snapshot = queryBus.ask(new FindOrder(orderId)).orElseThrow();
        assertEquals("CONFIRMED", snapshot.status());

        ProcessView view = processView(orderId);
        assertEquals(ProcessLifecycle.COMPLETED, view.lifecycle());
        assertEquals("ORDER_CONFIRMED", view.outcome().orElseThrow().value());
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
