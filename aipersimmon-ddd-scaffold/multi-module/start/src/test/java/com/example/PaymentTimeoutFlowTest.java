package com.example;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.processmanager.engine.runtime.DefaultProcessQuery;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.runtime.ProcessView;
import com.example.inventory.api.StockReleased;
import com.example.inventory.domain.stock.Sku;
import com.example.inventory.domain.stock.Stocks;
import com.example.ordering.application.order.FindOrder;
import com.example.ordering.application.order.PlaceOrder;
import com.example.ordering.domain.order.CancellationCategory;
import com.example.ordering.domain.order.OrderCancelledEvent;
import com.example.ordering.process.fulfilment.OrderFulfilmentDefinition;
import com.example.payment.adapter.PaymentRequestedListener;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The payment context goes silent. Nothing is declined, nothing fails — the request is simply never
 * answered, which is the failure mode a request/response test never reproduces and production
 * produces regularly.
 *
 * <p>Without a timer this order waits forever, holding its reserved stock. Nothing alerts: there is
 * no error, no dead letter, no retry — just one flow parked at {@code AWAITING_PAYMENT} and a stock
 * count that quietly no longer adds up. The deadline the flow arms alongside the payment request is
 * what turns that into an outcome.
 *
 * <p>The timeout takes the decline's compensation path unchanged — release the stock, then cancel
 * the order — so what is asserted here is deliberately the same set of observable facts as {@code
 * PaymentCompensationFlowTest}: stock handed back, order {@code CANCELLED}, instance {@code
 * COMPLETED} with {@code ORDER_CANCELLED}. Only the trigger differs, and that is the point: a new
 * way to fail did not need a new way to recover.
 */
@SpringBootTest(
    properties = {
      "aipersimmon.ddd.process-manager.effect-relay.poll-delay=200ms",
      "aipersimmon.ddd.process-manager.deadline-worker.poll-delay=200ms",
      "aipersimmon.ddd.outbox.poll-delay-ms=200",
      // Short enough to watch, long enough that reserving the stock beforehand comfortably wins.
      "ordering.fulfilment.payment-timeout=PT2S",
    })
@Import({TestInfrastructure.class, PaymentTimeoutFlowTest.Recording.class})
class PaymentTimeoutFlowTest {

  private static final Duration SETTLE = Duration.ofSeconds(30);

  @Autowired CommandBus commandBus;
  @Autowired QueryBus queryBus;
  @Autowired Stocks stocks;
  @Autowired DefaultProcessQuery process;
  @Autowired TimeoutRecorder recorder;

  /**
   * The payment context's inbound adapter, replaced by one that does nothing. A bean override
   * rather than a second {@code @Primary} bean: {@code @Primary} decides who gets injected, while
   * {@code @EventListener} registration is per bean — the real listener would still be registered
   * and would still answer. Overriding the definition is what actually silences it, and leaves the
   * rest of the flow real: the outbox still relays, the broker still delivers, the inbox still
   * deduplicates.
   */
  @MockitoBean PaymentRequestedListener silentPayment;

  @Test
  void aPaymentThatNeverAnswersEndsAsACancelledOrderWithItsStockBack() {
    int stockBefore = available("SKU-1");

    String orderId =
        commandBus.send(
            new PlaceOrder("CUST-1", List.of(new PlaceOrder.Line("SKU-1", 2, 100, "USD"))));

    // Nobody will ever answer the payment request; only the deadline can move this flow.
    await().atMost(SETTLE).untilAsserted(() -> assertEquals("CANCELLED", status(orderId)));

    await()
        .atMost(SETTLE)
        .untilAsserted(
            () -> {
              assertNotNull(
                  recorder.released(orderId),
                  "the timeout must release the stock before cancelling, exactly as a decline does");
              assertEquals(
                  CancellationCategory.PAYMENT_DECLINED, recorder.cancelledCategory(orderId));
            });

    await()
        .atMost(SETTLE)
        .untilAsserted(
            () ->
                assertEquals(
                    stockBefore, available("SKU-1"), "the held stock must come back exactly once"));

    await()
        .atMost(SETTLE)
        .untilAsserted(
            () -> {
              ProcessView view = processView(orderId);
              assertEquals(ProcessLifecycle.COMPLETED, view.lifecycle());
              assertEquals("ORDER_CANCELLED", view.outcome().orElseThrow().value());
              assertEquals("CANCELLED", view.step().value());
            });
  }

  private int available(String sku) {
    return stocks.findBySku(new Sku(sku)).orElseThrow().available();
  }

  private String status(String orderId) {
    return queryBus.ask(new FindOrder(orderId)).orElseThrow().status().name();
  }

  private ProcessView processView(String orderId) {
    return process
        .findRef(OrderFulfilmentDefinition.PROCESS_TYPE, new ProcessBusinessKey(orderId))
        .flatMap(process::find)
        .orElseThrow();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Recording {

    @Bean
    TimeoutRecorder timeoutRecorder() {
      return new TimeoutRecorder();
    }
  }

  /** Captures the two facts the compensation must produce, by order id. */
  static class TimeoutRecorder {

    private final Map<String, StockReleased> released = new ConcurrentHashMap<>();
    private final Map<String, CancellationCategory> cancelled = new ConcurrentHashMap<>();

    @EventListener
    void onReleased(EventEnvelope<StockReleased> envelope) {
      released.put(envelope.payload().orderId(), envelope.payload());
    }

    @EventListener
    void onCancelled(OrderCancelledEvent event) {
      cancelled.put(event.orderId().value(), event.category());
    }

    StockReleased released(String orderId) {
      return released.get(orderId);
    }

    CancellationCategory cancelledCategory(String orderId) {
      return cancelled.get(orderId);
    }
  }
}
