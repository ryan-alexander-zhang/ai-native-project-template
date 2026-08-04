package com.example.samples.s24;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.samples.s24.coupons.api.CouponCode;
import com.example.samples.s24.coupons.api.CouponQuote;
import com.example.samples.s24.coupons.api.CouponQuotes;
import com.example.samples.s24.coupons.api.CouponRedemptions;
import com.example.samples.s24.ordering.application.OrderQuery;
import com.example.samples.s24.ordering.application.OrderTotals;
import com.example.samples.s24.sharedkernel.api.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The first integration between an old context and a new one — both halves of it, and what the split costs.
 *
 * <p>The catalogue asks whether the first integration should be an event or a call. The answer this sample argues for is
 * <em>both, split by whether the answer is needed to decide or only to record</em>, and the tests below are the argument:
 *
 * <ul>
 *   <li>pricing cannot proceed without the discount, so the quote is a call, and its answer is in the same response as
 *       the price;
 *   <li>counting the redemption is a consequence, so it happens after commit and cannot fail the order;
 *   <li>and the gap between the two is real, measurable, and the price of choosing that split. The last test measures it.
 * </ul>
 */
class QuoteAndRedeemTest extends ContextTestBase {

  @Autowired private CouponQuotes quotes;
  @Autowired private CouponRedemptions redemptions;

  @Test
  void apercentageComesOffAndTheAnswerArrivesWithThePrice() {
    issuePercentCoupon("SAVE10", 10, 5);
    OrderTotals totals = placeOrder("ord-1", 5_000, "SAVE10");
    assertThat(totals.grossMinor()).isEqualTo(5_000);
    assertThat(totals.discountMinor()).isEqualTo(500);
    assertThat(totals.totalMinor()).isEqualTo(4_500);
    assertThat(totals.couponCode()).isEqualTo("SAVE10");
    assertThat(totals.couponRefusal()).isNull();
  }

  /**
   * A refused coupon prices the order at full price and says why, in the same response.
   *
   * <p>Which is the whole reason the quote is synchronous. A design where the customer discovers the refusal by comparing
   * two numbers is a design that generates support tickets, and no event can carry an answer back into a decision already
   * made.
   */
  @Test
  void arefusedCouponIsAnAnswerRatherThanAnError() {
    issueExpiredCoupon("LASTYEAR", 20);
    OrderTotals totals = placeOrder("ord-2", 5_000, "LASTYEAR");
    assertThat(totals.discountMinor()).isZero();
    assertThat(totals.totalMinor()).isEqualTo(5_000);
    assertThat(totals.couponCode()).as("nothing was applied, so nothing is recorded").isNull();
    assertThat(totals.couponRefusal()).isEqualTo("this coupon has expired");
  }

  @Test
  void anunknownCodeIsAlsoJustAnAnswer() {
    OrderTotals totals = placeOrder("ord-3", 5_000, "NOSUCHCODE");
    assertThat(totals.discountMinor()).isZero();
    assertThat(totals.couponRefusal()).isEqualTo("we do not recognise this coupon code");
  }

  /** Quoting consumes nothing. A customer editing their basket four times must not burn a single-use coupon. */
  @Test
  void quotingIsReadOnly() {
    issuePercentCoupon("ONCE", 50, 1);
    for (int i = 0; i < 4; i++) {
      CouponQuote quote = quotes.quote(new CouponCode("ONCE"), Money.of(1_000, GBP));
      assertThat(quote.accepted()).isTrue();
    }
    assertThat(couponRow("ONCE")).containsEntry("redemptions", 0);
    assertThat(redemptionCount("ONCE")).isZero();
  }

  /** The other half: after the order commits, the redemption is counted by the context map. */
  @Test
  void theredemptionIsCountedAfterTheOrderIsPlaced() {
    issuePercentCoupon("SAVE10", 10, 5);
    placeOrder("ord-4", 5_000, "SAVE10");
    assertThat(couponRow("SAVE10")).containsEntry("redemptions", 1);
    assertThat(redemptionCount("SAVE10")).isEqualTo(1);
    assertThat(jdbc.queryForObject(
            "SELECT amount_minor FROM s24_coupons_redemption WHERE coupon_code = 'SAVE10'",
            Long.class))
        .as("the receipt records what actually came off, not what the coupon says it is worth")
        .isEqualTo(500L);
  }

  /** An order with no coupon touches the coupons context not at all. */
  @Test
  void anorderWithoutACouponInvolvesNobody() {
    OrderTotals totals = placeOrder("ord-5", 5_000, null);
    assertThat(totals.discountMinor()).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM s24_coupons_redemption", Integer.class))
        .isZero();
  }

  /** A fixed-amount coupon never turns a small basket into a refund. */
  @Test
  void afixedAmountIsCappedAtTheOrderItIsAppliedTo() {
    issueFixedCoupon("TENOFF", 1_000, 5);
    OrderTotals totals = placeOrder("ord-6", 400, "TENOFF");
    assertThat(totals.discountMinor()).isEqualTo(400);
    assertThat(totals.totalMinor()).isZero();
  }

  /** What was agreed is a fact of the ordering context; re-reading the order does not re-quote the coupon. */
  @Test
  void areadOfTheOrderReportsThePriceItWasPlacedAt() {
    issuePercentCoupon("SAVE10", 10, 5);
    placeOrder("ord-7", 5_000, "SAVE10");
    jdbc.update("UPDATE s24_coupons_coupon SET percent_off = 90 WHERE code = 'SAVE10'");
    OrderTotals reread = queryBus.ask(new OrderQuery("ord-7"));
    assertThat(reread.discountMinor())
        .as("the coupon changed; the order did not")
        .isEqualTo(500);
  }

  /**
   * The sequential path is self-correcting, and that was worth measuring rather than assuming.
   *
   * <p>The expectation going in was that a single-use coupon would discount two orders, because a quote holds nothing. It
   * does not: each order is quoted <em>inside</em> its own command, and the first order's redemption has already landed by
   * the time the second is priced. So the second order is refused at quote time and pays full price, with a reason.
   *
   * <p>Which narrows the window considerably, and is the useful correction: it is not "between two orders", it is
   * <strong>between the quote and the redemption of one order</strong>. Two customers have to be checking out at the same
   * moment, not merely one after another. The next test measures that window directly.
   */
  @Test
  void asecondOrderOnASingleUseCouponIsRefusedAtQuoteTime() {
    issuePercentCoupon("ONCE", 50, 1);

    OrderTotals one = placeOrder("ord-8a", 1_000, "ONCE");
    OrderTotals two = placeOrder("ord-8b", 2_000, "ONCE");

    assertThat(one.discountMinor()).isEqualTo(500);
    assertThat(two.discountMinor())
        .as("the first redemption had already landed, so the second quote refused")
        .isZero();
    assertThat(two.couponRefusal())
        .isEqualTo("this coupon has already been used the maximum number of times");
    assertThat(couponRow("ONCE")).containsEntry("redemptions", 1);
  }

  /**
   * And here is the window that does exist, measured at the boundary rather than through two orders.
   *
   * <p>A quote is an answer about a moment. Between that moment and the redemption that follows it, somebody else's
   * redemption can land — which is exactly what two simultaneous checkouts do. The quote said yes; the redemption says no;
   * the order has already been priced at the discount.
   *
   * <p>That is not a bug in this code, it is the shape of the choice. The alternatives:
   *
   * <ul>
   *   <li><strong>hold at quote time</strong> — correct, and it turns the read into a write: every abandoned basket then
   *       needs releasing, with a timeout, which is a small process manager living inside a pricing call;
   *   <li><strong>redeem synchronously inside the order's transaction</strong> — also correct, and it puts two contexts'
   *       aggregates in one transaction, which is the coupling the boundary exists to prevent and which becomes a
   *       distributed transaction the day they are split;
   *   <li><strong>accept it and make it visible</strong> — this one. The refusal is returned and logged, and the receipts
   *       table shows one redemption against a coupon that discounted two orders, so it reconciles.
   * </ul>
   *
   * <p>Which of the three is right depends on what a coupon costs, and that is a business question. What is not negotiable
   * is knowing which one you shipped.
   */
  @Test
  void aquoteCanGoStaleBeforeTheRedemptionThatFollowsIt() {
    issuePercentCoupon("ONCE", 50, 1);

    // A basket is priced. This is the answer the customer is shown.
    CouponQuote quoted = quotes.quote(new CouponCode("ONCE"), Money.of(1_000, GBP));
    assertThat(quoted.accepted()).isTrue();
    assertThat(quoted.discount()).isEqualTo(Money.of(500, GBP));

    // Somebody else checks out first and uses the last redemption.
    placeOrder("ord-8-other", 2_000, "ONCE");
    assertThat(couponRow("ONCE")).containsEntry("redemptions", 1);

    // Now the first customer's order is redeemed — and it cannot be.
    assertThat(redemptions.redeem(new CouponCode("ONCE"), "ord-8-mine", Money.of(500, GBP)))
        .as("the quote said yes and the redemption says no; the price was already agreed")
        .isEqualTo(CouponRedemptions.Outcome.REFUSED);
    assertThat(couponRow("ONCE"))
        .as("nothing was written by the refusal — the decision was in memory and discarded")
        .containsEntry("redemptions", 1);
    assertThat(redemptionCount("ONCE"))
        .as("one receipt, and it names the order that got there first")
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT order_id FROM s24_coupons_redemption WHERE coupon_code = 'ONCE'",
                String.class))
        .isEqualTo("ord-8-other");
  }

  /**
   * Re-delivering the same order's placement counts once.
   *
   * <p>After-commit delivery is at-least-once in any arrangement worth having, so the redemption has to be idempotent by
   * order id — otherwise a retry costs the customer a use of their coupon.
   */
  @Test
  void asecondDeliveryOfTheSameOrderCountsOnce() {
    issuePercentCoupon("SAVE10", 10, 5);
    placeOrder("ord-9", 5_000, "SAVE10");
    assertThat(couponRow("SAVE10")).containsEntry("redemptions", 1);

    // Exactly what the listener does on a redelivery.
    assertThat(redemptions.redeem(new CouponCode("SAVE10"), "ord-9", Money.of(500, GBP)))
        .isEqualTo(CouponRedemptions.Outcome.ALREADY_REDEEMED);
    assertThat(couponRow("SAVE10")).containsEntry("redemptions", 1);
    assertThat(redemptionCount("SAVE10")).isEqualTo(1);
  }
}
