package com.example.samples.s25.refunds.domain;

import com.aipersimmon.ddd.core.model.Identifier;

/**
 * The refund's identity during the overlap: <strong>the legacy row's own {@code bigint}</strong>.
 *
 * <p>Which is the answer to the catalogue's third question, and it is the boring one on purpose. The
 * alternatives were considered and both are worse <em>for the transition</em>:
 *
 * <ul>
 *   <li><strong>switch to UUIDv7 now</strong> — the foreign key from {@code legacy_refunds.order_id}, the legacy
 *       code, and a decade of reports all reference the number. Changing identity and extracting an aggregate at
 *       the same time is two migrations wearing one hat;
 *   <li><strong>carry a UUID as the aggregate's identity and the bigint as a column</strong> — then every legacy
 *       join needs a lookup, immediately, for no benefit until the legacy code is gone.
 * </ul>
 *
 * <p>So the internal identity stays the number and the type hides it: nothing above this class knows the identity
 * is a database counter, which is what makes changing it later a change to this file and its mapping.
 *
 * <p>What is <em>not</em> the same decision is the identity handed <strong>outward</strong>. A number that means
 * "insertion order in one database" must never become an external contract — it leaks volume, it is guessable, and
 * it cannot survive a merge of two deployments. That is what {@code public_id} is for, and why V2 adds it before
 * any endpoint exists rather than after.
 *
 * <p>The library's UUIDv7 generator is untouched by all of this: it mints ids for the framework's own rows (outbox,
 * inbox), which this sample uses, and it has never had an opinion about a consumer's aggregate identity.
 */
public record RefundId(long value) implements Identifier {

  public RefundId {
    if (value <= 0) {
      throw new IllegalArgumentException(
          "a legacy refund id is a positive bigint; " + value + " means the row was never inserted");
    }
  }

  @Override
  public String toString() {
    return Long.toString(value);
  }
}
