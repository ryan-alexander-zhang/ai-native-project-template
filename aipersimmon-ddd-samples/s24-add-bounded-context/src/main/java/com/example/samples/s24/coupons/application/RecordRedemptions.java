package com.example.samples.s24.coupons.application;

import com.example.samples.s24.coupons.api.CouponCode;
import com.example.samples.s24.coupons.api.CouponRedemptions;
import com.example.samples.s24.coupons.domain.Coupon;
import com.example.samples.s24.coupons.domain.Coupons;
import com.example.samples.s24.sharedkernel.api.Money;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counting a use, idempotently, in this context's own transaction.
 *
 * <p><strong>Its own transaction, and that is the point of doing it after commit.</strong> The order is already
 * placed; nothing here may roll it back. So this runs in a transaction of its own, and its three outcomes are all
 * outcomes rather than failures — including the refusal, which the caller cannot fix.
 *
 * <p><strong>Idempotent by the receipt's primary key.</strong> {@code (coupon_code, order_id)} with an
 * {@code ON CONFLICT DO NOTHING}: the second delivery of the same order inserts nothing, so the counter is not bumped
 * and the answer is {@code ALREADY_REDEEMED}. After-commit delivery is at-least-once in any arrangement worth having,
 * and a redemption that double-counts turns a harmless retry into a coupon the customer loses.
 *
 * <p>The receipts live outside the aggregate for the reason S28 sets out at length: they bear no invariant between
 * them, and a popular coupon's child collection would be rewritten in full on every redemption. The <em>count</em> is
 * on the aggregate, because the limit is a rule.
 *
 * <p>Order of operations matters, and it is the opposite of the obvious one: <strong>decide on the aggregate first,
 * claim the receipt second, persist third.</strong> The decision is in memory and costs nothing to discard, so a
 * refusal writes nothing at all; the receipt is what makes a redelivery a no-op; and the counter reaches the database
 * only when both have said yes. Claiming the receipt first would leave a receipt behind for a redemption that was then
 * refused — after which the retry would report {@code ALREADY_REDEEMED} for something that never happened.
 */
@Service
class RecordRedemptions implements CouponRedemptions {

  private static final Logger log = LoggerFactory.getLogger(RecordRedemptions.class);

  private final Coupons coupons;
  private final RedemptionReceipts receipts;
  private final Clock clock;

  RecordRedemptions(Coupons coupons, RedemptionReceipts receipts, Clock clock) {
    this.coupons = coupons;
    this.receipts = receipts;
    this.clock = clock;
  }

  @Override
  @Transactional
  public Outcome redeem(CouponCode code, String orderId, Money discount) {
    Optional<Coupon> found = coupons.find(code);
    if (found.isEmpty()) {
      // A coupon that was quoted moments ago and is now gone. Nothing to count, and somebody should know.
      log.warn("order {} was discounted with coupon {}, which no longer exists", orderId, code);
      return Outcome.REFUSED;
    }
    Coupon coupon = found.get();
    if (!coupon.redeem()) {
      // The quote accepted it and the limit has since been reached. The order is already placed at the
      // discounted price, so this is a discrepancy, not a failure — see the api's own documentation.
      // Nothing has been written: the decision was in memory and is simply discarded.
      log.warn(
          "order {} was discounted with coupon {}, which is now past its limit of {}",
          orderId,
          code,
          coupon.maxRedemptions());
      return Outcome.REFUSED;
    }
    if (!receipts.record(code, orderId, discount, clock.instant())) {
      // Somebody already counted this order. The in-memory bump is discarded by not saving.
      return Outcome.ALREADY_REDEEMED;
    }
    coupons.save(coupon);
    return Outcome.REDEEMED;
  }
}
