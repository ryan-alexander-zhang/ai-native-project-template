package com.aipersimmon.ddd.outbox.engine.store;

import java.time.Instant;

/**
 * One poll's claim on a set of outbox rows: who holds it, the token that identifies this particular
 * claim, and when it expires.
 *
 * <p>The token is what makes a claim readable back — the store claims rows by stamping this token
 * on them and then selects exactly the rows carrying it, so "which rows did I win" is answered by
 * the database rather than guessed from an update count. It is fresh per claim, never reused.
 *
 * <p>{@code until} is the whole point of leasing rather than locking: an instance that is killed
 * mid-poll cannot release anything, so the rows it held become claimable again on their own once
 * this passes. Nothing else has to notice the instance died.
 *
 * <p>{@code owner} is diagnostics only — it answers "which node is sitting on these rows" when
 * operating a stuck relay, and is never used to decide anything. Fencing is the token's job.
 *
 * @param owner the node holding the claim; non-blank
 * @param token identifies this claim; non-blank and unique per claim
 * @param until when the claim expires and the rows become claimable again
 */
public record OutboxLease(String owner, String token, Instant until) {

  public OutboxLease {
    if (owner == null || owner.isBlank()) {
      throw new IllegalArgumentException("lease owner required");
    }
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("lease token required");
    }
    if (until == null) {
      throw new IllegalArgumentException("lease expiry required");
    }
  }
}
