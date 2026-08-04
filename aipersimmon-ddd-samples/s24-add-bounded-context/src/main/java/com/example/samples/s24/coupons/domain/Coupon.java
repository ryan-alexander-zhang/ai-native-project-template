package com.example.samples.s24.coupons.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.example.samples.s24.coupons.api.CouponCode;
import com.example.samples.s24.sharedkernel.api.Money;
import java.time.Instant;
import java.util.Optional;

/**
 * A coupon: a discount, a window, and a limit.
 *
 * <p>Two things about this class are the scenario rather than the domain.
 *
 * <p><strong>It is not published, and it never will be.</strong> Ordering names a coupon and cannot read one — see
 * {@code coupons.api}. Everything the outside world needs is an <em>answer</em>: an amount off, or a reason there
 * isn't one.
 *
 * <p><strong>It knows nothing about orders.</strong> Not even through {@code ordering.api}. A discount is computed
 * against an amount, and the amount is an argument; the aggregate has never heard of a basket, a line, or a customer.
 * That is not fastidiousness — it is the property that makes this context liftable, and it is checked:
 * {@code ArchitectureTest.nodomainKnowsAnotherContextExists} forbids any domain package from depending on another
 * context at all, published contract included. Cross-context collaboration is an application-layer job, because the
 * application layer is where a transaction, a failure and a retry can be reasoned about.
 *
 * <p>The identity is {@code CouponCode}, which <em>is</em> published. An identity has to be: a reference the outside
 * world cannot name is not a reference.
 */
@AggregateRoot
public final class Coupon extends AbstractAggregateRoot<CouponCode> {

  private final CouponCode code;
  private final CouponKind kind;
  private final Money value;
  private final int percentOff;
  private final Instant validFrom;
  private final Instant validUntil;
  private final int maxRedemptions;

  private int redemptions;

  private Coupon(
      CouponCode code,
      CouponKind kind,
      Money value,
      int percentOff,
      Instant validFrom,
      Instant validUntil,
      int maxRedemptions,
      int redemptions) {
    this.code = code;
    this.kind = kind;
    this.value = value;
    this.percentOff = percentOff;
    this.validFrom = validFrom;
    this.validUntil = validUntil;
    this.maxRedemptions = maxRedemptions;
    this.redemptions = redemptions;
  }

  /** A fixed amount off, capped at the amount it is applied to. */
  public static Coupon fixedAmount(
      CouponCode code, Money off, Instant validFrom, Instant validUntil, int maxRedemptions) {
    requireWindow(validFrom, validUntil);
    requireLimit(maxRedemptions);
    if (off.isNegative() || off.minor() == 0) {
      throw new IllegalArgumentException("a fixed-amount coupon must take something off, was " + off);
    }
    return new Coupon(
        code, CouponKind.FIXED, off, 0, validFrom, validUntil, maxRedemptions, 0);
  }

  /** A percentage off, rounded down. */
  public static Coupon percentOff(
      CouponCode code,
      int percent,
      String currency,
      Instant validFrom,
      Instant validUntil,
      int maxRedemptions) {
    requireWindow(validFrom, validUntil);
    requireLimit(maxRedemptions);
    if (percent < 1 || percent > 100) {
      throw new IllegalArgumentException("percent off must be 1..100, was " + percent);
    }
    return new Coupon(
        code,
        CouponKind.PERCENT,
        Money.zero(currency),
        percent,
        validFrom,
        validUntil,
        maxRedemptions,
        0);
  }

  public static Coupon reconstitute(
      CouponCode code,
      String kind,
      Money value,
      int percentOff,
      Instant validFrom,
      Instant validUntil,
      int maxRedemptions,
      int redemptions,
      long version) {
    Coupon coupon =
        new Coupon(
            code,
            CouponKind.valueOf(kind),
            value,
            percentOff,
            validFrom,
            validUntil,
            maxRedemptions,
            redemptions);
    coupon.restoreVersion(version);
    return coupon;
  }

  /**
   * Why this coupon cannot be used at {@code now}, or empty when it can.
   *
   * <p>A reason rather than a boolean, because the caller has somebody to tell. Phrased for a customer, since that
   * is where it ends up — a refusal reading {@code VALIDITY_WINDOW_VIOLATION} is a refusal nobody can act on.
   */
  public Optional<String> refusalAt(Instant now) {
    if (now.isBefore(validFrom)) {
      return Optional.of("this coupon is not valid yet");
    }
    if (now.isAfter(validUntil)) {
      return Optional.of("this coupon has expired");
    }
    if (redemptions >= maxRedemptions) {
      return Optional.of("this coupon has already been used the maximum number of times");
    }
    return Optional.empty();
  }

  /**
   * What comes off {@code amount}.
   *
   * <p>Never more than the amount itself, which is the invariant that keeps a fixed-amount coupon from turning a
   * small basket into a refund. Note that it does not check validity: the caller asks {@link #refusalAt} first, and
   * the two are separate because "is it usable" and "what is it worth" are separate questions with separate answers
   * to give.
   */
  public Money discountOn(Money amount) {
    Money raw =
        switch (kind) {
          case PERCENT -> amount.percent(percentOff);
          case FIXED -> value;
        };
    return raw.isGreaterThan(amount) ? amount : raw;
  }

  /**
   * Count one use.
   *
   * @return false when the limit is already reached — which can happen even though a quote accepted it moments ago,
   *     because a quote does not hold anything. That gap is the price of a read-only quote and it is measured rather
   *     than papered over; see {@code coupons.api.CouponRedemptions} and the analysis document's §6
   */
  public boolean redeem() {
    if (redemptions >= maxRedemptions) {
      return false;
    }
    redemptions++;
    return true;
  }

  @Override
  public CouponCode id() {
    return code;
  }

  public String kindName() {
    return kind.name();
  }

  public Money value() {
    return value;
  }

  public int percentOff() {
    return percentOff;
  }

  public Instant validFrom() {
    return validFrom;
  }

  public Instant validUntil() {
    return validUntil;
  }

  public int maxRedemptions() {
    return maxRedemptions;
  }

  public int redemptions() {
    return redemptions;
  }

  public String currency() {
    return value.currency();
  }

  private static void requireWindow(Instant from, Instant until) {
    if (from == null || until == null) {
      throw new IllegalArgumentException("a coupon needs a validity window");
    }
    if (!until.isAfter(from)) {
      throw new IllegalArgumentException("a coupon's window must end after it starts");
    }
  }

  private static void requireLimit(int maxRedemptions) {
    if (maxRedemptions < 1) {
      throw new IllegalArgumentException(
          "a coupon must allow at least one redemption, was " + maxRedemptions);
    }
  }
}
