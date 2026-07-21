package com.example.ordering.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.event.DomainEvent;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.ordering.domain.customer.CustomerId;
import com.example.ordering.domain.shared.Money;
import com.example.ordering.domain.shared.OrderingErrorCode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A runnable walk-through of the ordering context's most complex state change: cancelling an order
 * that has already entered fulfilment. Unlike the mechanical {@code confirm()} / {@code ship()}
 * moves — which a flat transition table decides — cancellation depends on <em>why</em> and on
 * <em>evidence</em>, and is arbitrated by {@link OrderLifecyclePolicy} while the {@link Order}
 * aggregate stays the sole mutator.
 *
 * <p>The headline is {@link CancellationReason.PaymentDeclinedAfterStockReleased}: an order under
 * fulfilment may only be cancelled for a payment decline once the reserved stock has actually been
 * released — and the type system makes that guarantee before the policy even runs.
 */
class ComplexOrderStateChangeDemoTest {

  private static final CustomerId CUSTOMER = new CustomerId("cust-1");
  private static final CustomerId SOMEONE_ELSE = new CustomerId("cust-2");

  private static Order placeReviewFreeOrder(OrderId id) {
    List<LineData> lines = List.of(new LineData("SKU-1", 2, Money.of(1_000, "USD")));
    return Order.place(id, CUSTOMER, lines, ReviewRequirement.notRequired());
  }

  /** Drive an order all the way to FULFILMENT_IN_PROGRESS, the pivotal state. */
  private static Order orderUnderFulfilment(OrderId id) {
    Order order = placeReviewFreeOrder(id);
    assertEquals(OrderStatus.READY_FOR_FULFILMENT, order.status());
    order.beginFulfilment();
    assertEquals(OrderStatus.FULFILMENT_IN_PROGRESS, order.status());
    return order;
  }

  @SuppressWarnings("unchecked")
  private static <E extends DomainEvent> E lastEventOfType(Order order, Class<E> type) {
    return (E)
        order.domainEvents().stream()
            .filter(type::isInstance)
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + " was recorded"));
  }

  private static OrderingErrorCode codeOf(DomainException ex) {
    return (OrderingErrorCode)
        ex.errorCode()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "expected a coded DomainException, got: " + ex.getMessage()));
  }

  @Nested
  @DisplayName("payment declined after stock release — the compensating cancellation")
  class PaymentDeclinedAfterStockRelease {

    @Test
    @DisplayName(
        "cancels the in-fulfilment order once both pieces of compensation evidence are present")
    void cancelsWithCompensationEvidence() {
      OrderId id = new OrderId("order-1");
      Order order = orderUnderFulfilment(id);

      // The saga has run the compensation: payment was declined, then the reserved stock
      // was released. Both facts are carried as evidence refs that name this very order.
      PaymentDeclineRef decline = new PaymentDeclineRef("pay-decline-1", id, "card_declined");
      StockReleaseRef release = new StockReleaseRef("stock-release-1", id);

      order.cancel(new CancellationReason.PaymentDeclinedAfterStockReleased(decline, release));

      assertEquals(OrderStatus.CANCELLED, order.status());
      OrderCancelledEvent event = lastEventOfType(order, OrderCancelledEvent.class);
      assertEquals(id, event.orderId());
      // The published event carries only the coarse category — never the internal evidence.
      assertEquals(CancellationCategory.PAYMENT_DECLINED, event.category());
    }

    @Test
    @DisplayName("the reason cannot even be constructed without stock-release evidence")
    void reasonRequiresStockReleaseEvidence() {
      OrderId id = new OrderId("order-1");
      PaymentDeclineRef decline = new PaymentDeclineRef("pay-decline-1", id, "card_declined");

      // This is the load-bearing guarantee: with no StockReleaseRef there is no legal reason,
      // so a saga that has not yet released stock literally cannot ask to cancel for this cause.
      assertThrows(
          DomainException.class,
          () -> new CancellationReason.PaymentDeclinedAfterStockReleased(decline, null));
    }

    @Test
    @DisplayName("evidence belonging to a different order is rejected by the policy")
    void rejectsEvidenceForAnotherOrder() {
      OrderId id = new OrderId("order-1");
      OrderId otherId = new OrderId("order-999");
      Order order = orderUnderFulfilment(id);

      // Well-formed evidence, but the stock release names a different order.
      PaymentDeclineRef decline = new PaymentDeclineRef("pay-decline-1", id, "card_declined");
      StockReleaseRef releaseForOther = new StockReleaseRef("stock-release-1", otherId);

      DomainException ex =
          assertThrows(
              DomainException.class,
              () ->
                  order.cancel(
                      new CancellationReason.PaymentDeclinedAfterStockReleased(
                          decline, releaseForOther)));
      assertEquals(OrderingErrorCode.COMPENSATION_EVIDENCE_ORDER_MISMATCH, codeOf(ex));
      assertEquals(
          OrderStatus.FULFILMENT_IN_PROGRESS,
          order.status(),
          "a rejected cancel must not mutate the order");
    }

    @Test
    @DisplayName("does not apply before fulfilment has started")
    void notApplicableBeforeFulfilment() {
      OrderId id = new OrderId("order-1");
      Order order = placeReviewFreeOrder(id); // still READY_FOR_FULFILMENT

      PaymentDeclineRef decline = new PaymentDeclineRef("pay-decline-1", id, "card_declined");
      StockReleaseRef release = new StockReleaseRef("stock-release-1", id);

      DomainException ex =
          assertThrows(
              DomainException.class,
              () ->
                  order.cancel(
                      new CancellationReason.PaymentDeclinedAfterStockReleased(decline, release)));
      assertEquals(OrderingErrorCode.PAYMENT_FAILURE_NOT_APPLICABLE, codeOf(ex));
    }
  }

  @Nested
  @DisplayName("the customer's self-cancel window")
  class CustomerCancellation {

    @Test
    @DisplayName("open before fulfilment, for the order's own customer")
    void allowedBeforeFulfilment() {
      OrderId id = new OrderId("order-1");
      Order order = placeReviewFreeOrder(id); // READY_FOR_FULFILMENT

      order.cancel(new CancellationReason.CustomerRequested(CUSTOMER));

      assertEquals(OrderStatus.CANCELLED, order.status());
      assertEquals(
          CancellationCategory.CUSTOMER_REQUESTED,
          lastEventOfType(order, OrderCancelledEvent.class).category());
    }

    @Test
    @DisplayName("closed once fulfilment has started")
    void closedAfterFulfilment() {
      OrderId id = new OrderId("order-1");
      Order order = orderUnderFulfilment(id);

      DomainException ex =
          assertThrows(
              DomainException.class,
              () -> order.cancel(new CancellationReason.CustomerRequested(CUSTOMER)));
      assertEquals(OrderingErrorCode.CUSTOMER_CANCELLATION_WINDOW_CLOSED, codeOf(ex));
    }

    @Test
    @DisplayName("never open to a different customer")
    void rejectedForAnotherCustomer() {
      OrderId id = new OrderId("order-1");
      Order order = placeReviewFreeOrder(id);

      DomainException ex =
          assertThrows(
              DomainException.class,
              () -> order.cancel(new CancellationReason.CustomerRequested(SOMEONE_ELSE)));
      assertEquals(OrderingErrorCode.NOT_ORDER_CUSTOMER, codeOf(ex));
    }
  }

  @Nested
  @DisplayName("terminal and review states")
  class OtherTransitions {

    @Test
    @DisplayName("a shipped order must enter the return flow, not be cancelled")
    void shippedOrderRequiresReturn() {
      OrderId id = new OrderId("order-1");
      Order order = orderUnderFulfilment(id);
      order.confirm();
      order.ship();
      assertEquals(OrderStatus.SHIPPED, order.status());

      PaymentDeclineRef decline = new PaymentDeclineRef("pay-decline-1", id, "card_declined");
      StockReleaseRef release = new StockReleaseRef("stock-release-1", id);
      DomainException ex =
          assertThrows(
              DomainException.class,
              () ->
                  order.cancel(
                      new CancellationReason.PaymentDeclinedAfterStockReleased(decline, release)));
      assertEquals(OrderingErrorCode.RETURN_REQUIRED, codeOf(ex));
    }

    @Test
    @DisplayName("an order needing review waits, then becomes fulfilment-eligible on approval")
    void reviewApprovalOpensFulfilment() {
      OrderId id = new OrderId("order-1");
      List<LineData> lines = List.of(new LineData("SKU-1", 1, Money.of(500, "USD")));
      Order order =
          Order.place(id, CUSTOMER, lines, ReviewRequirement.required(Set.of("high_value")));
      assertEquals(OrderStatus.AWAITING_REVIEW, order.status());

      order.approveReview(new ReviewDecisionRef("review-1", id, true));

      assertEquals(OrderStatus.READY_FOR_FULFILMENT, order.status());
      assertInstanceOf(
          OrderReadyForFulfilmentEvent.class,
          lastEventOfType(order, OrderReadyForFulfilmentEvent.class));
    }

    @Test
    @DisplayName("category reduction keeps the published event evidence-free")
    void categoryReductionIsTotal() {
      OrderId id = new OrderId("order-1");
      CancellationReason reason =
          new CancellationReason.InventoryUnavailable(
              new ReservationFailureRef("fail-1", id, "out_of_stock", "SKU-1 unavailable"));
      assertSame(CancellationCategory.INVENTORY_UNAVAILABLE, CancellationCategory.from(reason));
      assertTrue(reason instanceof CancellationReason.InventoryUnavailable);
    }
  }
}
