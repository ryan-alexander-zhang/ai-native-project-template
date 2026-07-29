package com.aipersimmon.ddd.outbox.engine.autoconfigure;

/** Metric names for the outbox SLIs, under a common prefix. */
final class OutboxMeters {

  private static final String PREFIX = "aipersimmon.outbox.";

  static final String PENDING = PREFIX + "pending";
  static final String OLDEST_PENDING_AGE = PREFIX + "oldest.pending.age";
  static final String CLAIM_LATENCY = PREFIX + "claim.latency";
  static final String DISPATCH_LATENCY = PREFIX + "dispatch.latency";
  static final String DEAD_LETTERED = PREFIX + "dead.lettered";
  static final String MARK_SENT_FAILURES = PREFIX + "mark.sent.failures";
  static final String RELEASED = PREFIX + "released";

  private OutboxMeters() {}
}
