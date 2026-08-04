package com.example.samples.s07.payments.application;

import java.time.Instant;
import java.util.List;

/**
 * The reconciler's candidate scan: which payments were unsettled a moment ago.
 *
 * <p>Advisory, like every scan of this shape (S11 argues it at length): between this query and the
 * command that acts on an id, the callback we gave up on may finally arrive. That is fine — the
 * aggregate decides, and a late callback and a reconciliation of the same payment meet as
 * {@link com.example.samples.s07.payments.domain.SettlementOutcome#DUPLICATE}.
 *
 * <p>Three things about the signature.
 *
 * <p>It returns <strong>ids</strong>. Each is then queried at the provider and settled in its own
 * transaction, so one payment the gateway answers strangely cannot roll back the other nine, and a
 * poison row keeps failing alone instead of stalling the channel.
 *
 * <p>It takes {@code requestedBefore} rather than reading a clock, so a test decides what "stuck" means
 * instead of sleeping.
 *
 * <p>It excludes the already-escalated, which is not an optimisation. A reconciler that re-raises the
 * same payment every tick produces an alert stream nobody reads, and the second alert is always less
 * informative than the first.
 */
public interface StalePayments {

  /**
   * Ids of payments that are not in a terminal state, were requested before {@code requestedBefore},
   * and carry no review flag — oldest first.
   *
   * @param limit hard ceiling, so a round is bounded and a backlog drains over rounds
   */
  List<String> findUnsettled(Instant requestedBefore, int limit);
}
