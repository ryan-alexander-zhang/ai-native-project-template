package com.example.samples.s07.payments.application;

import java.util.List;

/**
 * What one reconciliation round did. Nobody is waiting on this method, so what it does not report, it
 * does not do as far as anyone can tell.
 *
 * @param runId the correlation id every command of this round shares
 * @param scanned candidates the scan proposed
 * @param settled payments the provider had an answer for, and which moved
 * @param unchanged payments where the provider agreed with what we already knew
 * @param escalated payments handed to a human, with the reason
 * @param unreachable payments we could not ask about; left alone for the next round
 * @param awaiting payments the provider has no record of <em>yet</em> — most often because the charge
 *     request has not left our own outbox. Counted separately from {@code unreachable} because the two
 *     have different cures: one waits, the other retries. A round that is mostly {@code awaiting} means
 *     the reconciler is running sooner than the outbound channel can deliver, which is a configuration
 *     mistake and not a payments problem.
 */
public record ReconciliationReport(
    String runId,
    int scanned,
    int settled,
    int unchanged,
    List<Escalation> escalated,
    int unreachable,
    int awaiting) {

  public ReconciliationReport {
    escalated = escalated == null ? List.of() : List.copyOf(escalated);
  }

  /** One payment nobody could resolve automatically. */
  public record Escalation(String paymentId, String reason) {}
}
