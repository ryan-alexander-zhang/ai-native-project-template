package com.aipersimmon.ddd.web.spi;

import java.time.Duration;

/**
 * Makes an authorised write safe to retry: one key means one execution, and later attempts with
 * that key receive the first outcome. This is a reliability concern (safe retries) — distinct from
 * replay protection, which is a security concern; see {@link ReplayGuard}.
 *
 * <p>The lifecycle is deliberately three calls rather than "look it up, run, save it". A key is
 * claimed <em>before</em> the request executes, so a second attempt arriving while the first is
 * still in flight — the timeout-and-retry case idempotency exists for — is told the truth instead
 * of executing a second time. Recording only the finished response cannot do that: by the time
 * there is something to record, both side effects have already happened.
 *
 * <pre>
 *   claim(key, leaseTtl)
 *     ├─ Won        → execute, then complete(...) on an outcome, or abandon(...) to release
 *     ├─ InProgress → another attempt holds the claim; do not execute
 *     ├─ Replay     → return the stored outcome; do not execute
 *     └─ Mismatch   → the key names a different request; refuse
 * </pre>
 *
 * <p>Implementations must make {@link #claim} atomic across instances — exactly one caller can hold
 * a key at a time. A claim carries its own lease so a caller that dies mid-request cannot block the
 * key until the (much longer) response retention expires: once the lease passes, the next attempt
 * may take the claim over.
 */
public interface IdempotencyStore {

  /**
   * Atomically take the claim for {@code key}, or report what already holds it.
   *
   * @param key the full identity of this attempt, including who is calling
   * @param leaseTtl how long this claim stays valid without being completed, after which another
   *     attempt may take it over
   */
  IdempotencyClaim claim(IdempotencyKey key, Duration leaseTtl);

  /**
   * Record the outcome for a claim this caller won, so later attempts replay it.
   *
   * @param ttl how long the outcome is retained — the retry window offered to clients, typically
   *     far longer than the claim lease
   */
  void complete(IdempotencyKey key, StoredResponse response, Duration ttl);

  /**
   * Release a claim without an outcome, leaving the key free for a later attempt.
   *
   * <p>Used when the request produced nothing worth replaying: a transient server failure, where
   * freezing the failure under the key for the whole retention window would defeat the retry the
   * key was issued for.
   */
  void abandon(IdempotencyKey key);
}
