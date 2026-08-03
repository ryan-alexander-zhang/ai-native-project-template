package com.example.payment.domain;

/**
 * The scaffold's default {@link AuthorizationPolicy}: authorise any amount up to a ceiling, decline
 * anything above it. A deterministic stand-in for a real gateway that makes the compensation path
 * exercisable from a test by simply ordering enough value.
 *
 * <p>Zero is authorised by its own branch rather than by falling under the ceiling. A zero-amount
 * order — a gift, a fully discounted basket — is a real thing ordering can produce, and a real
 * gateway has nothing to charge for it, so the round-trip is skipped rather than made with an
 * amount no gateway would accept. Writing it as a branch also keeps the decision visible: it would
 * otherwise be an unremarked consequence of {@code 0 <= ceiling}, and a later ceiling change could
 * take it away without anyone noticing.
 *
 * <p>The ceiling arrives through the constructor rather than a {@code static final} field, so a
 * deployment sets it ({@code payment.authorization.ceiling-minor}) without a rebuild. {@link
 * #DEFAULT_CEILING_MINOR} is the demo value and is what the tests pin themselves to, so a
 * deployment changing the property cannot break them.
 */
public final class CeilingAuthorizationPolicy implements AuthorizationPolicy {

  /** The scaffold's demo ceiling, in minor units. */
  public static final long DEFAULT_CEILING_MINOR = 50_000L;

  /** The code a decline carries. Part of this policy's vocabulary, not the port's. */
  public static final String DECLINE_CODE = "payment.amount-exceeds-ceiling";

  private final long ceilingMinor;

  public CeilingAuthorizationPolicy(long ceilingMinor) {
    if (ceilingMinor < 0) {
      throw new IllegalArgumentException(
          "ceilingMinor must be >= 0, was "
              + ceilingMinor
              + " — a negative ceiling would decline"
              + " every payment including the zero-amount ones this policy authorises by design");
    }
    this.ceilingMinor = ceilingMinor;
  }

  /** The scaffold's default: {@link #DEFAULT_CEILING_MINOR}. */
  public CeilingAuthorizationPolicy() {
    this(DEFAULT_CEILING_MINOR);
  }

  @Override
  public PaymentDecision decide(Amount amount) {
    if (amount.amountMinor() == 0L) {
      return new PaymentDecision.Authorized();
    }
    if (amount.amountMinor() <= ceilingMinor) {
      return new PaymentDecision.Authorized();
    }
    return new PaymentDecision.Declined(
        DECLINE_CODE,
        "amount "
            + amount.amountMinor()
            + " "
            + amount.currency()
            + " exceeds the authorisation ceiling");
  }
}
