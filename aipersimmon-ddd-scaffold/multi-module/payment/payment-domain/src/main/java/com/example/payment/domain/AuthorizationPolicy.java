package com.example.payment.domain;

/**
 * The payment authorization rule, expressed as a pure function of the amount. This reference
 * implementation authorises any charge up to {@link #AUTHORISATION_CEILING_MINOR} and declines
 * anything above it — a deterministic stand-in for a real gateway that makes the compensation
 * path exercisable from a test by simply ordering enough value.
 */
public final class AuthorizationPolicy {

    /** Charges at or below this amount (in minor units) are authorised; above it, declined. */
    public static final long AUTHORISATION_CEILING_MINOR = 50_000L;

    public static final String DECLINE_CODE = "payment.amount-exceeds-ceiling";

    public PaymentDecision decide(long amountMinor, String currency) {
        if (amountMinor <= AUTHORISATION_CEILING_MINOR) {
            return new PaymentDecision.Authorized();
        }
        return new PaymentDecision.Declined(
                DECLINE_CODE,
                "amount " + amountMinor + " " + currency + " exceeds the authorisation ceiling");
    }
}
