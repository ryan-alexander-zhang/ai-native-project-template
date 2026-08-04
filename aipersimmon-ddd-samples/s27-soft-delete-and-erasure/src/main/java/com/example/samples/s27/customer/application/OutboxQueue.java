package com.example.samples.s27.customer.application;

/**
 * How many announcements about a subject are still waiting to go out.
 *
 * <p><strong>An application-layer port over a framework table, which needs justifying.</strong> Nothing else
 * in this series reads the outbox from business code, and the general rule is right: the outbox is
 * infrastructure, and a handler that inspects it has coupled a use case to a delivery mechanism.
 *
 * <p>The exception is erasure, and the reason is that <em>the queue's contents are personal data</em>. An
 * unsent row for this customer contains their address; sending it after the erasure creates a fresh copy of
 * what was supposed to be gone, and deleting it leaves every consumer permanently wrong about a change that
 * really happened. Neither is acceptable, so the erasure has to be <em>ordered</em> after the queue drains —
 * and asking "is it drained" is the only way to order it.
 *
 * <p>What this deliberately is not: a way to send, cancel or rewrite anything. One question, one answer.
 */
public interface OutboxQueue {

  /** Unsent announcements whose subject is {@code subject}. */
  long unsentFor(String subject);
}
