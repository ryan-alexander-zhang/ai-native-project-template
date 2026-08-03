package com.example.samples.s16.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.core.rule.Specification;
import org.junit.jupiter.api.Test;

/**
 * The other half of the rule vocabulary: a specification answers instead of throwing, and a domain
 * service holds behaviour that belongs to neither object it needs.
 */
class SpecificationAndServiceTest {

  private static final Money PRICE = Money.of(Order.CURRENCY, "100.00");
  private static final Specification<Order> FREE_SHIPPING =
      new EligibleForFreeShipping(Money.of(Order.CURRENCY, "500.00"));

  @Test
  void aSpecificationAnswersRatherThanRefusing() {
    // No exception either way: not being eligible is an ordinary outcome to branch on.
    assertThat(FREE_SHIPPING.isSatisfiedBy(orderOf(2))).isFalse();
    assertThat(FREE_SHIPPING.isSatisfiedBy(orderOf(6))).isTrue();
  }

  @Test
  void specificationsCompose() {
    Specification<Order> eligibleAndLive = FREE_SHIPPING.and(new NotCancelled());
    Order big = orderOf(6);

    assertThat(eligibleAndLive.isSatisfiedBy(big)).isTrue();

    big.cancel();
    assertThat(eligibleAndLive.isSatisfiedBy(big)).isFalse();
    // and/or/not are defaults on the interface, so a negation needs nothing new either.
    assertThat(new NotCancelled().not().isSatisfiedBy(big)).isTrue();
  }

  @Test
  void theDomainServiceUsesBothObjectsAndOwnsNeither() {
    LoyaltyDiscount discount = new LoyaltyDiscount();
    Order order = orderOf(3); // 300.00

    assertThat(discount.discountFor(order, LoyaltyTier.STANDARD))
        .isEqualTo(Money.zero(Order.CURRENCY));
    assertThat(discount.discountFor(order, LoyaltyTier.SILVER))
        .isEqualTo(Money.of(Order.CURRENCY, "15.00"));
    assertThat(discount.discountFor(order, LoyaltyTier.GOLD))
        .isEqualTo(Money.of(Order.CURRENCY, "30.00"));
  }

  private static Order orderOf(int quantity) {
    Order order = Order.draft(new OrderId("order-1"), new CustomerId("customer-1"));
    order.addLine(new LineId("line-1"), new Sku("SKU-1"), PRICE, quantity);
    return order;
  }
}
