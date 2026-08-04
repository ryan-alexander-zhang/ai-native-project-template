package com.example.samples.s07.payments.application;

import com.example.samples.s07.payments.domain.GatewayOutcome;

/**
 * What the provider says when we ask about a payment — translated, and exhaustive.
 *
 * <p>Sealed on purpose. The reconciler has to handle four genuinely different situations, and three of
 * them are the ones that get forgotten when the answer is modelled as a nullable outcome. A
 * {@code switch} over a sealed type will not compile with a branch missing, which is a cheaper way to
 * remember them than an incident.
 *
 * <p>{@link NoRecord} and {@link Unintelligible} are the two that matter most, because neither means
 * failure. "I have never heard of this payment" and "I answered with a code you do not know" are both
 * states in which the money may or may not have moved, and a reconciler that maps either of them to
 * {@code FAILED} will eventually mark a charged customer as unpaid.
 */
public sealed interface GatewayReport {

  /** The provider has this charge and told us where it stands. */
  record Reported(GatewayOutcome outcome, String gatewayRef) implements GatewayReport {}

  /**
   * The provider has no record of this charge. Either the request never arrived or it was lost on their
   * side; from here the two are indistinguishable, and both are unsafe to guess about.
   */
  record NoRecord() implements GatewayReport {}

  /**
   * The provider answered with something this adapter cannot map — a result code added after we were
   * written, or a response shape that changed. The detail is for the human who will read the log.
   */
  record Unintelligible(String detail) implements GatewayReport {}

  /** The provider could not be reached, or answered with an error. Transient by assumption. */
  record Unreachable(String detail) implements GatewayReport {}
}
