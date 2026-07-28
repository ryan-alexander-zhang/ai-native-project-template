package com.example;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.processmanager.engine.runtime.DefaultProcessQuery;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.runtime.ProcessView;
import com.example.inventory.adapter.messaging.OrderReadyForFulfilmentListener;
import com.example.inventory.domain.stock.Sku;
import com.example.inventory.domain.stock.Stocks;
import com.example.ordering.application.order.FindOrder;
import com.example.ordering.application.order.PlaceOrder;
import com.example.ordering.domain.order.CancellationCategory;
import com.example.ordering.domain.order.OrderCancelledEvent;
import com.example.ordering.process.fulfilment.OrderFulfilmentDefinition;
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
 * The inventory context goes silent — the mirror of {@code PaymentTimeoutFlowTest}, and the reason
 * that test's premise was wrong.
 *
 * <p>The flow used to arm one timer, on payment, justified like this: <em>"payment is the only step
 * whose answer comes from outside and may simply never arrive"</em>. Inventory looked different
 * because it answers {@code StockReservationFailed} when it cannot reserve — but it answers that
 * only when it judges a <strong>business</strong> failure. A technical one (an optimistic-lock
 * conflict against a concurrent reservation of the same SKU, a validation failure, a database
 * outage) throws out of {@code ReserveStockHandler} and publishes nothing at all. To this flow that
 * silence is the payment context's silence exactly (issue-00068).
 *
 * <p>What made it worse than the payment case: the order is already in {@code
 * FULFILMENT_IN_PROGRESS} by then, so the customer's self-cancel window has closed, and there was
 * no deadline, no {@code max-lifetime}, and no operator endpoint that could move it. A silently
 * parked order with no route out, for anyone.
 *
 * <p>Silence here is cheaper to recover from than at payment, and the assertions say so: nothing
 * has been reserved yet, so there is no stock to hand back and the order goes straight to
 * cancellation — the same branch a refusal takes, with only the recorded code to tell them apart.
 */
@SpringBootTest(
    properties = {
      "aipersimmon.ddd.process-manager.effect-relay.poll-delay=200ms",
      "aipersimmon.ddd.process-manager.deadline-worker.poll-delay=200ms",
      "aipersimmon.ddd.outbox.poll-delay-ms=200",
      // Short enough to watch it happen.
      "ordering.fulfilment.stock-timeout=PT2S",
    })
@Import({TestInfrastructure.class, StockReservationTimeoutFlowTest.Recording.class})
class StockReservationTimeoutFlowTest {

  private static final Duration SETTLE = Duration.ofSeconds(30);

  @Autowired CommandBus commandBus;
  @Autowired QueryBus queryBus;
  @Autowired Stocks stocks;
  @Autowired DefaultProcessQuery process;
  @Autowired CancellationRecorder recorder;

  /**
   * Inventory's inbound adapter, replaced by one that does nothing — the same override {@code
   * PaymentTimeoutFlowTest} uses on the payment side, and for the same reason: overriding the bean
   * definition is what unregisters its {@code @EventListener}, where a second {@code @Primary} bean
   * would leave the real one still listening and still answering.
   */
  @MockitoBean OrderReadyForFulfilmentListener silentInventory;

  @Test
  void aReservationThatIsNeverAnsweredEndsAsACancelledOrder() {
    int stockBefore = available("SKU-1");

    String orderId =
        commandBus.send(
            new PlaceOrder("CUST-1", List.of(new PlaceOrder.Line("SKU-1", 2, 100, "USD"))));

    // Nobody will ever answer the reservation request; only the deadline can move this flow.
    await().atMost(SETTLE).untilAsserted(() -> assertEquals("CANCELLED", status(orderId)));

    await()
        .atMost(SETTLE)
        .untilAsserted(
            () ->
                assertEquals(
                    CancellationCategory.INVENTORY_UNAVAILABLE,
                    recorder.categoryFor(orderId),
                    "silence from inventory is recorded as inventory being unavailable"));

    assertEquals(
        stockBefore,
        available("SKU-1"),
        "nothing was ever reserved, so nothing had to be released — unlike the payment timeout,"
            + " this compensation has no stock to hand back");

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
    return queryBus.ask(new FindOrder(orderId)).orElseThrow().status();
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
    CancellationRecorder cancellationRecorder() {
      return new CancellationRecorder();
    }
  }

  /** Captures why each order was cancelled, by order id. */
  static class CancellationRecorder {

    private final Map<String, CancellationCategory> cancelled = new ConcurrentHashMap<>();

    @EventListener
    void onCancelled(OrderCancelledEvent event) {
      cancelled.put(event.orderId().value(), event.category());
    }

    CancellationCategory categoryFor(String orderId) {
      return cancelled.get(orderId);
    }
  }
}
