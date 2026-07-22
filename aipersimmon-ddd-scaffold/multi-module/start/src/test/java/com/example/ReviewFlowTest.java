package com.example;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.inventory.domain.stock.Sku;
import com.example.inventory.domain.stock.Stocks;
import com.example.ordering.application.order.ApproveReview;
import com.example.ordering.application.order.FindOrder;
import com.example.ordering.application.order.PlaceOrder;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * End-to-end for the manual-review path (the review branch the {@code ManualReviewPolicy} enables).
 * An order containing a restricted SKU is held in {@code AWAITING_REVIEW} and reserves nothing —
 * proving that reservation and the fulfilment process are driven by <em>readiness</em>, not by mere
 * placement. Approving the review clears the order, at which point the same asynchronous cascade a
 * review-free order takes runs to completion and the order confirms.
 */
@SpringBootTest(
    properties = {
      "aipersimmon.ddd.process-manager.jdbc.effect-relay.poll-delay=200ms",
      "aipersimmon.ddd.process-manager.jdbc.deadline-worker.poll-delay=1h",
      "aipersimmon.ddd.outbox.poll-delay-ms=200",
    })
@Import(TestInfrastructure.class)
class ReviewFlowTest {

  private static final Duration SETTLE = Duration.ofSeconds(30);

  @Autowired CommandBus commandBus;
  @Autowired QueryBus queryBus;
  @Autowired Stocks stocks;

  private String status(String orderId) {
    return queryBus.ask(new FindOrder(orderId)).orElseThrow().status();
  }

  private int available(String sku) {
    return stocks.findBySku(new Sku(sku)).orElseThrow().available();
  }

  @Test
  void aRestrictedOrderIsHeldForReviewReservesNothingThenConfirmsOnApproval() {
    int stockBefore = available("SKU-RESTRICTED");

    // SKU-RESTRICTED is on the review watchlist, so the order is held — not cleared for fulfilment.
    String orderId =
        commandBus.send(
            new PlaceOrder(
                "CUST-1", List.of(new PlaceOrder.Line("SKU-RESTRICTED", 1, 100, "USD"))));

    // Placement is synchronous: the order is AWAITING_REVIEW the moment the command returns, and
    // because nothing was announced to inventory, no stock is reserved for it.
    assertEquals("AWAITING_REVIEW", status(orderId));
    assertEquals(stockBefore, available("SKU-RESTRICTED"), "a held order must reserve no stock");

    // Approving the review clears the order; from there the normal async cascade confirms it.
    commandBus.send(new ApproveReview(orderId));

    await().atMost(SETTLE).untilAsserted(() -> assertEquals("CONFIRMED", status(orderId)));
    await()
        .atMost(SETTLE)
        .untilAsserted(
            () ->
                assertEquals(
                    stockBefore - 1,
                    available("SKU-RESTRICTED"),
                    "the approved order reserves its stock exactly once"));
  }
}
