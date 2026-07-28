package com.example.payment.domain;

/**
 * The payment authorization rule, expressed as a pure function of the amount. This reference
 * implementation authorises any payment up to {@link #AUTHORISATION_CEILING_MINOR} and declines
 * anything above it — a deterministic stand-in for a real gateway that makes the compensation path
 * exercisable from a test by simply ordering enough value.
 *
 * <p>Zero is authorised by its own branch rather than by falling under the ceiling (issue-00075). A
 * zero-amount order — a gift, a fully discounted basket — is a real thing ordering can produce, and
 * a real gateway has nothing to charge for it, so the round-trip is skipped rather than made with
 * an amount no gateway would accept. Writing it as a branch also keeps the decision visible: it
 * would otherwise be an unremarked consequence of {@code 0 <= ceiling}, and a later ceiling change
 * could take it away without anyone noticing.
 */
public final class AuthorizationPolicy {

  /** Payments at or below this amount (in minor units) are authorised; above it, declined. */
  public static final long AUTHORISATION_CEILING_MINOR = 50_000L;

  public static final String DECLINE_CODE = "payment.amount-exceeds-ceiling";

  public PaymentDecision decide(long amountMinor, String currency) {
    if (amountMinor == 0L) {
      return new PaymentDecision.Authorized();
    }
    if (amountMinor <= AUTHORISATION_CEILING_MINOR) {
      return new PaymentDecision.Authorized();
    }
    return new PaymentDecision.Declined(
        DECLINE_CODE,
        "amount " + amountMinor + " " + currency + " exceeds the authorisation ceiling");
  }
}
