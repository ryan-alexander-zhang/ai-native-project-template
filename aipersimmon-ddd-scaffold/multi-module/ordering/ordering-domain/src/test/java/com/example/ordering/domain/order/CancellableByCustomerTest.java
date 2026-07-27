package com.example.ordering.domain.order;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.ordering.domain.customer.CustomerId;
import com.example.ordering.domain.shared.Money;
import com.example.ordering.domain.shared.OrderingErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The question and the refusal must be the same rule.
 *
 * <p>{@code CancellableByCustomer} answers "may they?"; {@code OrderLifecyclePolicy} refuses when
 * they may not. If those two ever disagree, a client is told it can cancel and then cannot, or is
 * shown no button for something it was entitled to do. So the tests below check both sides of every
 * case, and the two share one statement of the window.
 */
class CancellableByCustomerTest {

  private static final CustomerId OWNER = new CustomerId("CUST-1");
  private static final CustomerId SOMEONE_ELSE = new CustomerId("CUST-2");

  @Test
  void anOrderStillBeforeFulfilmentMayBeCancelledByItsOwner() {
    Order order = orderAt(OrderStatus.READY_FOR_FULFILMENT);

    assertTrue(new CancellableByCustomer(OWNER).isSatisfiedBy(order));
    order.cancel(new CancellationReason.CustomerRequested(OWNER)); // and the write agrees
    assertTrue(order.status() == OrderStatus.CANCELLED);
  }

  @Test
  void anOrderHeldForReviewMayAlsoBeCancelled() {
    assertTrue(
        new CancellableByCustomer(OWNER).isSatisfiedBy(orderAt(OrderStatus.AWAITING_REVIEW)));
  }

  @Test
  void onceFulfilmentStartsTheAnswerIsNoAndSoIsTheWrite() {
    Order order = orderAt(OrderStatus.FULFILMENT_IN_PROGRESS);

    assertFalse(new CancellableByCustomer(OWNER).isSatisfiedBy(order));

    DomainException refused =
        assertThrows(
            DomainException.class,
            () -> order.cancel(new CancellationReason.CustomerRequested(OWNER)));
    // The specification says no; the invariant says which rule said no. That is the difference
    // between the two, and why the model needs both.
    assertTrue(
        refused.errorCode().orElseThrow() == OrderingErrorCode.CUSTOMER_CANCELLATION_WINDOW_CLOSED);
  }

  @Test
  void someoneElsesOrderIsNotYoursToCancel() {
    Order order = orderAt(OrderStatus.READY_FOR_FULFILMENT);

    assertFalse(new CancellableByCustomer(SOMEONE_ELSE).isSatisfiedBy(order));

    DomainException refused =
        assertThrows(
            DomainException.class,
            () -> order.cancel(new CancellationReason.CustomerRequested(SOMEONE_ELSE)));
    assertTrue(refused.errorCode().orElseThrow() == OrderingErrorCode.NOT_ORDER_CUSTOMER);
  }

  @Test
  void theRulesCompose() {
    // Both halves must hold: the right person, at a permitted time. Neither alone is enough.
    Order tooLate = orderAt(OrderStatus.FULFILMENT_IN_PROGRESS);
    Order open = orderAt(OrderStatus.READY_FOR_FULFILMENT);

    assertFalse(
        new CancellableByCustomer(OWNER).isSatisfiedBy(tooLate), "right person, wrong time");
    assertFalse(new CancellableByCustomer(SOMEONE_ELSE).isSatisfiedBy(open), "wrong person");
    assertTrue(CancellableByCustomer.BEFORE_FULFILMENT.isSatisfiedBy(open.status()));
    assertFalse(CancellableByCustomer.BEFORE_FULFILMENT.isSatisfiedBy(tooLate.status()));
    // not() is the same rule read the other way — the window that has closed.
    assertTrue(CancellableByCustomer.BEFORE_FULFILMENT.not().isSatisfiedBy(tooLate.status()));
  }

  @Test
  void nothingIsNotCancellable() {
    assertFalse(new CancellableByCustomer(OWNER).isSatisfiedBy(null));
  }

  private static Order orderAt(OrderStatus status) {
    return Order.reconstitute(
        new OrderId("order-1"),
        OWNER,
        List.of(new LineData("SKU-1", 1, Money.of(100, "USD"))),
        status,
        1);
  }
}
