package com.example.payment.domain;

/**
 * Decides whether a payment of a given amount is authorized, producing a {@link PaymentDecision}.
 *
 * <p>Note what the return type says: this policy <em>decides</em>, it does not <em>refuse</em>. An
 * over-limit amount is a legitimate {@link PaymentDecision.Declined} outcome that the ordering
 * context compensates for, not a rejected command. That is why no {@code DomainException} appears
 * anywhere on this path — a decline is an answer, and the flow has a branch for it.
 *
 * <p><strong>A port, not a class.</strong> It used to be a final class with a {@code public static
 * final} ceiling, instantiated with {@code new} in a field of {@code AuthorizePaymentHandler}. For
 * the one rule in this context that every real deployment must replace, that was exactly backwards.
 * {@link CeilingAuthorizationPolicy} remains as the default; substituting a real payment provider
 * now means declaring a bean, not editing a handler.
 *
 * <p>Two obligations on an implementation, both load-bearing for the flow around it:
 *
 * <ul>
 *   <li><strong>Do not throw.</strong> A throw escapes the handler, rolls the transaction back and
 *       publishes nothing — and silence is indistinguishable from a dead broker, so the order sits
 *       until ordering's PAYMENT deadline cancels it for a reason unrelated to the truth.
 *       issue-00075 was this exact shape: a zero amount failed a constraint, no outcome event was
 *       ever published, and the symptom appeared two minutes later and nowhere near the cause.
 *       Return {@code Declined} instead.
 *   <li><strong>Be deterministic for a given amount.</strong> {@code AuthorizePaymentHandler} calls
 *       this only when an operation has never been decided, and reuses the recorded decision on
 *       every redelivery — precisely so a non-deterministic policy cannot give one operation two
 *       outcomes. An implementation that calls a provider inherits that protection, but must also
 *       carry the operation id as the provider's own idempotency key.
 * </ul>
 */
public interface AuthorizationPolicy {

  /**
   * @param amountMinor the amount in the currency's minor unit; may be {@code 0}, which a real
   *     provider would never be asked to charge but which this domain accepts and must not treat as
   *     a failure
   * @param currency ISO-4217 code
   */
  PaymentDecision decide(long amountMinor, String currency);
}
