package com.example.samples.s28.reconciliation.application;

import java.util.UUID;

/**
 * A name for this process, for the lease to be held under.
 *
 * <p>It has to be unique <em>per process</em>, not per host and not per deployment: the whole point of the
 * owner column is that a worker whose lease lapsed can be told apart from the worker that took its job over,
 * and two processes sharing a name make those two indistinguishable — the fence in {@code ExportJob} would let
 * the stalled one report an outcome for the fresh one's work.
 *
 * <p>Which is why it ends in a random suffix rather than being a hostname or a pod name. Those look nicer in a
 * log and they are exactly what a restarted container reuses.
 */
final class WorkerIdentity {

  private WorkerIdentity() {}

  static String ofThisInstance() {
    String host = System.getenv().getOrDefault("HOSTNAME", "local");
    return host + "/" + UUID.randomUUID();
  }
}
