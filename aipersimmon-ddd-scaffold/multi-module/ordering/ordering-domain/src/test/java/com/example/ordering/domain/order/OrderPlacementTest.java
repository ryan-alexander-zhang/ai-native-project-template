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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrderPlacementTest {

  private static final OrderId ID = new OrderId("order-1");
  private static final CustomerId CUSTOMER = new CustomerId("cust-1");

  private static List<LineData> oneLine() {
    return List.of(new LineData("SKU-1", 2, Money.of(1_000, "USD")));
  }

  private static OrderingErrorCode codeOf(DomainException ex) {
    return (OrderingErrorCode) ex.errorCode().orElseThrow();
  }

  @Test
  void reviewFreeOrderIsReadyAndRecordsPlacedAndReadyEvents() {
    Order order = Order.place(ID, CUSTOMER, oneLine(), ReviewRequirement.notRequired());

    assertEquals(OrderStatus.READY_FOR_FULFILMENT, order.status());
    assertSame(ID, order.id());
    assertSame(CUSTOMER, order.customerId());
    List<DomainEvent> events = order.domainEvents();
    assertInstanceOf(OrderPlacedEvent.class, events.get(0));
    assertInstanceOf(OrderReadyForFulfilmentEvent.class, events.get(1));
    assertEquals(Money.of(2_000, "USD"), ((OrderPlacedEvent) events.get(0)).total());
  }

  @Test
  void reviewRequiredOrderAwaitsReviewAndRecordsOnlyPlaced() {
    Order order =
        Order.place(ID, CUSTOMER, oneLine(), ReviewRequirement.required(Set.of("high_value")));

    assertEquals(OrderStatus.AWAITING_REVIEW, order.status());
    assertEquals(1, order.domainEvents().size());
    assertInstanceOf(OrderPlacedEvent.class, order.domainEvents().get(0));
  }

  @Test
  void rejectsAnEmptyOrder() {
    DomainException empty =
        assertThrows(
            DomainException.class,
            () -> Order.place(ID, CUSTOMER, List.of(), ReviewRequirement.notRequired()));
    assertEquals(OrderingErrorCode.ORDER_EMPTY, codeOf(empty));

    DomainException nullLines =
        assertThrows(
            DomainException.class,
            () -> Order.place(ID, CUSTOMER, null, ReviewRequirement.notRequired()));
    assertEquals(OrderingErrorCode.ORDER_EMPTY, codeOf(nullLines));
  }

  @Test
  void rejectsTooManyLines() {
    List<LineData> many = new ArrayList<>();
    for (int i = 0; i < 101; i++) {
      many.add(new LineData("SKU-" + i, 1, Money.of(100, "USD")));
    }

    DomainException ex =
        assertThrows(
            DomainException.class,
            () -> Order.place(ID, CUSTOMER, many, ReviewRequirement.notRequired()));
    assertEquals(OrderingErrorCode.TOO_MANY_LINES, codeOf(ex));
  }

  @Test
  void exactlyTheMaximumNumberOfLinesIsAllowed() {
    List<LineData> hundred = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      hundred.add(new LineData("SKU-" + i, 1, Money.of(100, "USD")));
    }

    Order order = Order.place(ID, CUSTOMER, hundred, ReviewRequirement.notRequired());

    assertEquals(
        OrderStatus.READY_FOR_FULFILMENT, order.status(), "100 lines is at the limit, not over");
  }

  @Test
  void reconstituteWithNoLinesHasNoTotal() {
    Order empty = Order.reconstitute(ID, CUSTOMER, List.of(), OrderStatus.CONFIRMED);

    assertThrows(DomainException.class, empty::total);
  }

  @Test
  void rejectsAMissingReviewRequirement() {
    assertThrows(DomainException.class, () -> Order.place(ID, CUSTOMER, oneLine(), null));
  }

  @Test
  void rejectsDuplicateSkus() {
    List<LineData> dupes =
        List.of(
            new LineData("SKU-1", 1, Money.of(100, "USD")),
            new LineData("SKU-1", 2, Money.of(100, "USD")));

    DomainException ex =
        assertThrows(
            DomainException.class,
            () -> Order.place(ID, CUSTOMER, dupes, ReviewRequirement.notRequired()));
    assertEquals(OrderingErrorCode.DUPLICATE_SKU, codeOf(ex));
  }

  @Test
  void totalSumsEveryLineSubtotal() {
    List<LineData> lines =
        List.of(
            new LineData("SKU-1", 2, Money.of(1_000, "USD")),
            new LineData("SKU-2", 1, Money.of(500, "USD")));

    Order order = Order.place(ID, CUSTOMER, lines, ReviewRequirement.notRequired());

    assertEquals(Money.of(2_500, "USD"), order.total());
  }

  @Test
  void lineDataRoundTripsThroughReconstitute() {
    Order placed = Order.place(ID, CUSTOMER, oneLine(), ReviewRequirement.notRequired());

    Order restored = Order.reconstitute(ID, CUSTOMER, placed.lineData(), OrderStatus.CONFIRMED);

    assertEquals(OrderStatus.CONFIRMED, restored.status(), "reconstitute sets the given status");
    assertTrue(restored.domainEvents().isEmpty(), "reconstitution records no events");
    assertEquals(placed.total(), restored.total());
  }

  @Test
  void approveReviewRejectsAMismatchedOrNullDecision() {
    Order order =
        Order.place(ID, CUSTOMER, oneLine(), ReviewRequirement.required(Set.of("high_value")));

    DomainException nullDecision =
        assertThrows(DomainException.class, () -> order.approveReview(null));
    assertEquals(OrderingErrorCode.REVIEW_DECISION_ORDER_MISMATCH, codeOf(nullDecision));

    DomainException wrongOrder =
        assertThrows(
            DomainException.class,
            () -> order.approveReview(new ReviewDecisionRef("rev-1", new OrderId("other"), true)));
    assertEquals(OrderingErrorCode.REVIEW_DECISION_ORDER_MISMATCH, codeOf(wrongOrder));
  }

  @Test
  void approveReviewRejectedWhenNotAwaitingReview() {
    Order order = Order.place(ID, CUSTOMER, oneLine(), ReviewRequirement.notRequired());

    DomainException ex =
        assertThrows(
            DomainException.class,
            () -> order.approveReview(new ReviewDecisionRef("rev-1", ID, true)));
    assertEquals(OrderingErrorCode.ORDER_NOT_AWAITING_REVIEW, codeOf(ex));
  }
}
