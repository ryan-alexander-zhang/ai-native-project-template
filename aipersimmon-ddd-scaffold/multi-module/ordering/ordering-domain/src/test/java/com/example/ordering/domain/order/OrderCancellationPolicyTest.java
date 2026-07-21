package com.example.ordering.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.ordering.domain.customer.CustomerId;
import com.example.ordering.domain.shared.Money;
import com.example.ordering.domain.shared.OrderingErrorCode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The inventory- and review-triggered cancellation paths of OrderLifecyclePolicy. */
class OrderCancellationPolicyTest {

  private static final CustomerId CUSTOMER = new CustomerId("cust-1");

  private static List<LineData> oneLine() {
    return List.of(new LineData("SKU-1", 1, Money.of(1_000, "USD")));
  }

  private static Order underFulfilment(OrderId id) {
    Order order = Order.place(id, CUSTOMER, oneLine(), ReviewRequirement.notRequired());
    order.beginFulfilment();
    return order;
  }

  private static Order awaitingReview(OrderId id) {
    return Order.place(id, CUSTOMER, oneLine(), ReviewRequirement.required(Set.of("high_value")));
  }

  private static OrderingErrorCode codeOf(DomainException ex) {
    return (OrderingErrorCode) ex.errorCode().orElseThrow();
  }

  @Test
  void cancelRejectsANullReason() {
    Order order = underFulfilment(new OrderId("order-1"));
    assertThrows(DomainException.class, () -> order.cancel(null));
  }

  @Test
  void inventoryFailureCancelsAnOrderUnderFulfilment() {
    OrderId id = new OrderId("order-1");
    Order order = underFulfilment(id);

    order.cancel(
        new CancellationReason.InventoryUnavailable(
            new ReservationFailureRef("fail-1", id, "out_of_stock", "SKU-1")));

    assertEquals(OrderStatus.CANCELLED, order.status());
    OrderCancelledEvent event =
        (OrderCancelledEvent)
            order.domainEvents().stream()
                .filter(OrderCancelledEvent.class::isInstance)
                .reduce((a, b) -> b)
                .orElseThrow();
    assertEquals(CancellationCategory.INVENTORY_UNAVAILABLE, event.category());
  }

  @Test
  void inventoryFailureDoesNotApplyBeforeFulfilment() {
    OrderId id = new OrderId("order-1");
    Order order = Order.place(id, CUSTOMER, oneLine(), ReviewRequirement.notRequired());

    DomainException ex =
        assertThrows(
            DomainException.class,
            () ->
                order.cancel(
                    new CancellationReason.InventoryUnavailable(
                        new ReservationFailureRef("fail-1", id, "out_of_stock", "SKU-1"))));
    assertEquals(OrderingErrorCode.INVENTORY_FAILURE_NOT_APPLICABLE, codeOf(ex));
  }

  @Test
  void inventoryFailureForAnotherOrderIsRejected() {
    OrderId id = new OrderId("order-1");
    Order order = underFulfilment(id);

    DomainException ex =
        assertThrows(
            DomainException.class,
            () ->
                order.cancel(
                    new CancellationReason.InventoryUnavailable(
                        new ReservationFailureRef(
                            "fail-1", new OrderId("other"), "out_of_stock", "SKU-1"))));
    assertEquals(OrderingErrorCode.RESERVATION_FAILURE_ORDER_MISMATCH, codeOf(ex));
  }

  @Test
  void reviewRejectionCancelsAnOrderAwaitingReview() {
    OrderId id = new OrderId("order-1");
    Order order = awaitingReview(id);

    order.cancel(new CancellationReason.ReviewRejected(new ReviewDecisionRef("rev-1", id, false)));

    assertEquals(OrderStatus.CANCELLED, order.status());
  }

  @Test
  void reviewRejectionDoesNotApplyOnceOutOfReview() {
    OrderId id = new OrderId("order-1");
    Order order = Order.place(id, CUSTOMER, oneLine(), ReviewRequirement.notRequired());

    DomainException ex =
        assertThrows(
            DomainException.class,
            () ->
                order.cancel(
                    new CancellationReason.ReviewRejected(
                        new ReviewDecisionRef("rev-1", id, false))));
    assertEquals(OrderingErrorCode.ORDER_NOT_AWAITING_REVIEW, codeOf(ex));
  }

  @Test
  void reviewRejectionForAnotherOrderIsRejected() {
    OrderId id = new OrderId("order-1");
    Order order = awaitingReview(id);

    DomainException ex =
        assertThrows(
            DomainException.class,
            () ->
                order.cancel(
                    new CancellationReason.ReviewRejected(
                        new ReviewDecisionRef("rev-1", new OrderId("other"), false))));
    assertEquals(OrderingErrorCode.REVIEW_DECISION_ORDER_MISMATCH, codeOf(ex));
  }

  @Test
  void paymentDeclineForAnotherOrderIsRejected() {
    OrderId id = new OrderId("order-1");
    Order order = underFulfilment(id);
    // The decline names a different order (exercises the first half of the || mismatch check).
    PaymentDeclineRef decline = new PaymentDeclineRef("pay-1", new OrderId("other"), "declined");
    StockReleaseRef release = new StockReleaseRef("rel-1", id);

    DomainException ex =
        assertThrows(
            DomainException.class,
            () ->
                order.cancel(
                    new CancellationReason.PaymentDeclinedAfterStockReleased(decline, release)));
    assertEquals(OrderingErrorCode.COMPENSATION_EVIDENCE_ORDER_MISMATCH, codeOf(ex));
  }
}
