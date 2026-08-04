package com.example.samples.s28.reconciliation.application;

import com.example.samples.s28.reconciliation.domain.ExportJobId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Taking a job, and the one place in this sample that deliberately does not go through the aggregate.
 *
 * <p><strong>Why a claim cannot be a version-checked write.</strong> The optimistic lock is built for a race
 * that nobody should be entering: two writers touched the same aggregate, one is refused, and the refusal is
 * information. A claim is the opposite — N workers are <em>meant</em> to compete for the next job, and N-1 of
 * them losing is not news. Expressed as read-then-save it would also be wrong rather than merely noisy: every
 * worker reads the same oldest queued job, one wins, and the rest have to loop and read again, so a fleet of
 * ten spends nine tenths of its polls colliding on one row.
 *
 * <p>So the claim is SQL: candidates selected {@code FOR UPDATE SKIP LOCKED}, then an {@code UPDATE} that
 * re-checks the condition it was chosen on. That is the shape the library uses for its own outbox relay and
 * process-effect relay, down to the three columns — an owner, an expiry, and a conditional update — and this
 * sample copies it rather than inventing a variant. S11 named this boundary and left it open: work with
 * nothing to version has nothing to arbitrate two instances, and then you claim it before doing it.
 *
 * <p><strong>What that costs, and what pays for it.</strong> Raw SQL and version-checked writes over one table
 * only coexist if the SQL advances the version too. Otherwise a cancellation that loaded the job a moment
 * before the claim still commits — the version it checks is the one it read — and a job ends up CANCELLED
 * while a worker runs it to completion. The claim statement therefore carries {@code version = version + 1},
 * and a negative control in the analysis document measures what happens when it does not.
 */
public interface ExportClaims {

  /**
   * Take the oldest claimable job, if there is one.
   *
   * <p>Claimable means queued, or running under a lease that has lapsed. The second half is what makes a
   * killed worker recoverable without anybody intervening: it released nothing, so its lease simply runs out
   * and the job becomes available again — the same reasoning the library gives for its own leases.
   *
   * @param owner who is claiming; recorded so the fence in {@code ExportJob} has something to compare
   * @param lease how long the claim is good for without a heartbeat
   * @return the job that was claimed, already RUNNING and already attributed, or empty
   */
  Optional<ExportJobId> claimNext(String owner, Duration lease, Instant now);

  /**
   * Push the lease forward while the work is still going.
   *
   * <p>Note what this must <em>not</em> do: bump the version. The worker is holding a loaded aggregate it
   * intends to write when the export finishes, and a heartbeat that advanced the version would fence the
   * worker against itself — the job would be abandoned by the only process still working on it, every lease
   * period, forever.
   *
   * @return false if the claim is gone, which is the worker's cue to stop early rather than finish work
   *     nobody will accept
   */
  boolean heartbeat(ExportJobId id, String owner, Instant leaseUntil);
}
