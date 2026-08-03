package com.example.samples.s16.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.state.IllegalStateTransitionException;
import com.aipersimmon.ddd.core.state.Transitions;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The transition table: what is legal, and what a refusal is called. */
class OrderLifecycleTest {

  private static final Money PRICE = Money.of(Order.CURRENCY, "10.00");

  @Test
  void theHappyPathWalksDraftToPlacedToPaid() {
    Order order = draftWithOneLine();

    order.place();
    assertThat(order.status()).isEqualTo(OrderStatus.PLACED);
    order.pay();
    assertThat(order.status()).isEqualTo(OrderStatus.PAID);

    assertThat(order.domainEvents())
        .containsExactly(
            new OrderPlaced(order.id(), order.customerId(), order.total()),
            new OrderPaid(order.id(), order.total()));
  }

  @Test
  void payingADraftIsRefusedWithTheDestinationsCode() {
    Order order = draftWithOneLine();

    assertThatThrownBy(order::pay)
        .isInstanceOf(IllegalStateTransitionException.class)
        .hasMessageContaining("DRAFT -> PAID")
        .extracting(thrown -> ((IllegalStateTransitionException) thrown).errorCode())
        .isEqualTo(Optional.of(OrderingErrorCode.ORDER_NOT_PAYABLE));
  }

  @Test
  void bothEdgesIntoCancelledCarryTheSameRefusalCode() {
    Order fromDraft = draftWithOneLine();
    fromDraft.cancel();
    assertThat(fromDraft.status()).isEqualTo(OrderStatus.CANCELLED);

    Order fromPlaced = draftWithOneLine();
    fromPlaced.place();
    fromPlaced.cancel();
    assertThat(fromPlaced.status()).isEqualTo(OrderStatus.CANCELLED);

    // A paid order cannot be cancelled, and the refusal is named after the destination.
    Order paid = draftWithOneLine();
    paid.place();
    paid.pay();
    assertThatThrownBy(paid::cancel)
        .extracting(thrown -> ((IllegalStateTransitionException) thrown).errorCode())
        .isEqualTo(Optional.of(OrderingErrorCode.ORDER_NOT_CANCELLABLE));
  }

  @Test
  void declaringTwoCodesForOneDestinationFailsWhileTheTableIsBuilt() {
    ErrorCode other = OrderingErrorCode.ORDER_NOT_PLACEABLE;

    // Not a runtime surprise on some later request: the table refuses to be built.
    assertThatThrownBy(
            () ->
                Transitions.<OrderStatus>of()
                    .allow(
                        OrderStatus.DRAFT,
                        OrderStatus.CANCELLED,
                        OrderingErrorCode.ORDER_NOT_CANCELLABLE)
                    .allow(OrderStatus.PLACED, OrderStatus.CANCELLED, other))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("conflicting refusal codes for destination CANCELLED");
  }

  @Test
  void permitsAsksWithoutThrowing() {
    Transitions<OrderStatus> table =
        Transitions.<OrderStatus>of().allow(OrderStatus.DRAFT, OrderStatus.PLACED);

    assertThat(table.permits(OrderStatus.DRAFT, OrderStatus.PLACED)).isTrue();
    assertThat(table.permits(OrderStatus.PLACED, OrderStatus.DRAFT)).isFalse();
  }

  private static Order draftWithOneLine() {
    Order order = Order.draft(new OrderId("order-1"), new CustomerId("customer-1"));
    order.addLine(new LineId("line-1"), new Sku("SKU-1"), PRICE, 2);
    return order;
  }
}
