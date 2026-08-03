package com.example.samples.s06.ordering.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandPrecheck;
import org.springframework.stereotype.Component;

/**
 * The remote call, in the one place it can safely go: <strong>before the transaction opens</strong>.
 *
 * <p>The framework runs prechecks between validation (order 100) and the transaction interceptor (order
 * 200). Everything about this sample's shape follows from those two numbers:
 *
 * <ul>
 *   <li><strong>No database connection is held while the network waits.</strong> The same call made on the
 *       handler's first line would run inside the transaction, and a connection would sit idle for the
 *       whole round trip. One slow dependency then amplifies into an exhausted connection pool — the
 *       failure mode where a service dies of something that was merely slow somewhere else.
 *   <li><strong>A refusal costs nothing to undo.</strong> When the answer is no, or when there is no
 *       answer, the transaction was never started: there is no partial write, no rollback, and no
 *       compensation to write. A test asserts the order table is untouched.
 * </ul>
 *
 * <p><strong>What this cannot give you</strong>, and the sample would be dishonest to skip it: a precheck
 * is <em>advisory by construction</em>. The world may change between the check and the commit, so the risk
 * answer is a point-in-time judgement and not an invariant this service enforces. That is fine here —
 * risk is a judgement — but if the remote answer must be atomic with the write (a reservation, a credit
 * hold), a synchronous call cannot provide it at all: you need the other side to hold something on your
 * behalf, which is a distributed transaction or a saga (S9/S10), not a question.
 *
 * <p>Prechecks also run on every dispatch, including redeliveries, so they must be safe to repeat — which
 * is the second reason the callee exposes a query rather than a command.
 */
@Component
class RiskPrecheck implements CommandPrecheck<PlaceOrder> {

  private final RiskAssessments riskAssessments;

  RiskPrecheck(RiskAssessments riskAssessments) {
    this.riskAssessments = riskAssessments;
  }

  @Override
  public void check(PlaceOrder command, CommandContext context) {
    RiskAssessments.RiskDecision decision =
        riskAssessments.assess(command.customerId(), command.amountCents());
    if (!decision.approved()) {
      // Refusing by throwing is the precheck contract. The exception is this context's, carrying the
      // callee's reason as text — never its error code.
      throw new RiskRejectedException(decision.reason());
    }
    // A RiskUnavailableException from the port is deliberately NOT caught here: failing closed is the
    // decision, and it is made by not writing a catch block. Failing open — placing the order when risk
    // cannot be reached — is a legitimate business choice for some products, and it would belong here,
    // explicitly, with the reason written down. What must not happen is for it to be the accident of a
    // swallowed exception.
  }
}
