package com.example.samples.s24;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.samples.s24.coupons.api.CouponCode;
import com.example.samples.s24.coupons.domain.Coupon;
import com.example.samples.s24.sharedkernel.api.Money;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The coupon aggregate, with no ordering context anywhere in sight.
 *
 * <p>The mirror of {@code OrderTest}: a discount is computed against an amount, and the amount is an argument. Nothing here
 * knows what a basket is, which is what makes the context liftable.
 */
class CouponTest {

  private static final Instant NOW = Instant.parse("2026-07-01T10:00:00Z");
  private static final Instant EARLIER = NOW.minusSeconds(3_600);
  private static final Instant LATER = NOW.plusSeconds(3_600);

  private static Coupon tenPercent(int maxRedemptions) {
    return Coupon.percentOff(new CouponCode("SAVE10"), 10, "GBP", EARLIER, LATER, maxRedemptions);
  }

  @Test
  void apercentageIsTakenOffTheAmountItIsAskedAbout() {
    assertThat(tenPercent(5).discountOn(Money.of(5_000, "GBP"))).isEqualTo(Money.of(500, "GBP"));
  }

  @Test
  void afixedAmountNeverExceedsWhatItIsAppliedTo() {
    Coupon tenner =
        Coupon.fixedAmount(new CouponCode("TENOFF"), Money.of(1_000, "GBP"), EARLIER, LATER, 5);
    assertThat(tenner.discountOn(Money.of(400, "GBP"))).isEqualTo(Money.of(400, "GBP"));
    assertThat(tenner.discountOn(Money.of(5_000, "GBP"))).isEqualTo(Money.of(1_000, "GBP"));
  }

  /** A reason a customer can act on, not a code only the service understands. */
  @Test
  void arefusalExplainsItselfInWordsSomebodyCanRead() {
    Coupon notYet =
        Coupon.percentOff(new CouponCode("SOON"), 10, "GBP", LATER, LATER.plusSeconds(60), 5);
    assertThat(notYet.refusalAt(NOW)).contains("this coupon is not valid yet");

    Coupon expired =
        Coupon.percentOff(
            new CouponCode("GONE"), 10, "GBP", EARLIER.minusSeconds(60), EARLIER, 5);
    assertThat(expired.refusalAt(NOW)).contains("this coupon has expired");
  }

  @Test
  void thelimitIsWhatRefusesTheLastRedemption() {
    Coupon once = tenPercent(1);
    assertThat(once.refusalAt(NOW)).isEmpty();
    assertThat(once.redeem()).isTrue();
    assertThat(once.redeem()).as("the second one is refused rather than throwing").isFalse();
    assertThat(once.refusalAt(NOW))
        .contains("this coupon has already been used the maximum number of times");
  }

  @Test
  void acouponNeedsAWindowThatEndsAfterItStartsAndAtLeastOneUse() {
    assertThatThrownBy(
            () -> Coupon.percentOff(new CouponCode("BAD"), 10, "GBP", LATER, EARLIER, 5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must end after it starts");
    assertThatThrownBy(
            () -> Coupon.percentOff(new CouponCode("BAD"), 10, "GBP", EARLIER, LATER, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one redemption");
  }

  @Test
  void acodeHasAShape() {
    assertThatThrownBy(() -> new CouponCode("lower-case"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be 3..32 characters");
    assertThat(new CouponCode("SAVE-10").value()).isEqualTo("SAVE-10");
  }
}
