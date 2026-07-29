package com.aipersimmon.ddd.outbox;

import java.util.Optional;

/**
 * Answers where an integration event is externalized to, so the writer can record that on the row
 * instead of leaving it to be re-decided when the row is dispatched.
 *
 * <p>Re-deciding is what made the loss silent. Reach is declared by an annotation on the event
 * class, so it is a property of the code that is deployed <em>now</em>, not of the event that was
 * written then. Drop an {@code @Externalized} on a version bump, or run a rolling deploy, and a row
 * written as externalized finds no route at dispatch time, falls through to in-process delivery,
 * and is marked sent — no exception, no dead letter, nothing lagging. Resolving it in the writing
 * transaction makes the destination as durable as the payload.
 *
 * <p>A transport starter supplies the implementation ({@code aipersimmon-ddd-messaging-kafka}
 * resolves {@code @Externalized} targets to topic names). With no transport installed there is
 * nowhere external to go, and {@link #ALL_IN_PROCESS} says so: every event is local, which is the
 * correct answer for that deployment rather than a degraded one.
 */
public interface EventDestinations {

  /**
   * The destination this {@code (type, version)} is externalized to, or empty when it is delivered
   * in process. The pair is the one the publisher stamps on the row, so nothing has to reconstruct
   * the event to answer.
   */
  Optional<String> destinationFor(String type, int version);

  /** Nothing is externalized: the deployment has no transport, so every event is in-process. */
  EventDestinations ALL_IN_PROCESS = (type, version) -> Optional.empty();
}
