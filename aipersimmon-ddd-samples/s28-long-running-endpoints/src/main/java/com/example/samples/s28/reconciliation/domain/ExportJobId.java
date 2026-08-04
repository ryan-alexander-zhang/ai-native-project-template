package com.example.samples.s28.reconciliation.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/**
 * A job's identity, and the reason this scenario needs no idempotency-key store.
 *
 * <p>The client supplies it. That single decision makes the whole asynchronous contract idempotent for
 * free: {@code PUT /exports/{id}} twice is one job, because the second request names the job that already
 * exists. S2 needed a key store because the resource it created had a server-assigned id, so there was
 * nothing in the request that could identify the first attempt's outcome.
 *
 * <p>It also sidesteps a mismatch that is easy to walk into. A stored idempotency outcome has a retention
 * window — hours, typically — while a job has a lifetime, which for a month-end export may be days. Once
 * the window is the shorter of the two, a client retrying an accepted-but-unfinished request gets a
 * <em>second</em> job, and the contract that was supposed to prevent duplicates has produced one. A
 * client-supplied id has no window: the resource is the record.
 */
@ValueObject
public record ExportJobId(String value) implements Identifier {

  public ExportJobId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("export job id must not be blank");
    }
    if (value.length() > 64) {
      throw new IllegalArgumentException("export job id must be at most 64 characters");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
