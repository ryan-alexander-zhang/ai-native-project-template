package com.example.ordering.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.core.event.DomainEvent;
import com.aipersimmon.ddd.core.state.IllegalStateTransitionException;
import com.example.ordering.domain.customer.CustomerId;
import com.example.ordering.domain.shared.Money;
import com.example.ordering.domain.shared.OrderingErrorCode;
import com.example.ordering.domain.shared.Sku;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The mechanical forward transitions (begin/confirm/ship): each advances and records its event. */
class OrderLifecycleTransitionsTest {

  private static final CustomerId CUSTOMER = new CustomerId("cust-1");

  private static Order readyOrder() {
    return Order.place(
        new OrderId("order-1"),
        CUSTOMER,
        List.of(new LineData(new Sku("SKU-1"), 1, Money.of(1_000, "USD"))),
        ReviewRequirement.notRequired());
  }

  private static DomainEvent lastEvent(Order order) {
    List<DomainEvent> events = order.domainEvents();
    return events.get(events.size() - 1);
  }

  @Test
  void beginFulfilmentAdvancesAndRecordsItsEvent() {
    Order order = readyOrder();

    order.beginFulfilment();

    assertEquals(OrderStatus.FULFILMENT_IN_PROGRESS, order.status());
    assertInstanceOf(OrderFulfilmentStartedEvent.class, lastEvent(order));
  }

  @Test
  void confirmAdvancesAndRecordsItsEvent() {
    Order order = readyOrder();
    order.beginFulfilment();

    order.confirm();

    assertEquals(OrderStatus.CONFIRMED, order.status());
    assertInstanceOf(OrderConfirmedEvent.class, lastEvent(order));
  }

  @Test
  void shipAdvancesAndRecordsItsEvent() {
    Order order = readyOrder();
    order.beginFulfilment();
    order.confirm();

    order.ship();

    assertEquals(OrderStatus.SHIPPED, order.status());
    assertInstanceOf(OrderShippedEvent.class, lastEvent(order));
  }

  /**
   * Every refused mechanical move carries a stable code, declared once in the transition table.
   * Before the table could name refusals, these three reached the edge as codeless exceptions while
   * every cancel/review refusal had its own {@code OrderingErrorCode} — the same aggregate, two
   * error contracts.
   */
  @Test
  void illegalForwardTransitionsAreRejectedWithTheirDeclaredCodes() {
    // beginFulfilment requires READY_FOR_FULFILMENT; an awaiting-review order is not there yet.
    Order awaiting =
        Order.place(
            new OrderId("order-2"),
            CUSTOMER,
            List.of(new LineData(new Sku("SKU-1"), 1, Money.of(1_000, "USD"))),
            ReviewRequirement.required(Set.of("high_value")));
    IllegalStateTransitionException begin =
        assertThrows(IllegalStateTransitionException.class, awaiting::beginFulfilment);
    assertEquals(OrderingErrorCode.ORDER_NOT_READY_FOR_FULFILMENT, begin.errorCode().orElseThrow());

    // confirm requires FULFILMENT_IN_PROGRESS; a merely-ready order is not there.
    IllegalStateTransitionException confirm =
        assertThrows(IllegalStateTransitionException.class, readyOrder()::confirm);
    assertEquals(OrderingErrorCode.ORDER_NOT_UNDER_FULFILMENT, confirm.errorCode().orElseThrow());

    // ship requires CONFIRMED; an in-fulfilment order is not there.
    Order inFulfilment = readyOrder();
    inFulfilment.beginFulfilment();
    IllegalStateTransitionException ship =
        assertThrows(IllegalStateTransitionException.class, inFulfilment::ship);
    assertEquals(OrderingErrorCode.ORDER_NOT_CONFIRMED, ship.errorCode().orElseThrow());
  }
}
