package com.example.samples.s07.payments.domain;

/**
 * What a gateway notification means, translated.
 *
 * <p>Three members, where the provider has dozens of result codes and will add more. That reduction is
 * the anticorruption layer's actual product: the aggregate reasons about "accepted, charged, refused",
 * and the knowledge that {@code 51} means refused lives in one class in {@code infrastructure}. A
 * fourth member for "we could not interpret this" is deliberately absent — an uninterpretable
 * notification is not an outcome, and giving it a name here would invite somebody to store it.
 */
public enum GatewayOutcome {

  /** The gateway has the request. Nothing has been decided. */
  ACCEPTED(PaymentStatus.SUBMITTED),

  /** Charged. */
  SUCCEEDED(PaymentStatus.SUCCEEDED),

  /** Refused, for a reason that is the gateway's business and not ours to re-litigate. */
  FAILED(PaymentStatus.FAILED);

  private final PaymentStatus status;

  GatewayOutcome(PaymentStatus status) {
    this.status = status;
  }

  PaymentStatus status() {
    return status;
  }
}
