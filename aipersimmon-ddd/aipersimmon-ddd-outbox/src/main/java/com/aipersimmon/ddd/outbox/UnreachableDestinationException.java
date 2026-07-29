package com.aipersimmon.ddd.outbox;

/**
 * A row names an external destination but the active {@link OutboxDispatcher} has admitted it
 * cannot reach one ({@link OutboxDispatcher#reachesExternalTargets()}).
 *
 * <p>The destination was decided in the writing transaction, so this says the deployment changed
 * underneath already-written work: the transport was removed, or replaced by one that stops at the
 * process boundary. Delivering the row locally would mark it sent while it never left the process,
 * which is the loss that recording the destination exists to end.
 *
 * <p>Deliberately <em>not</em> in the permanent-failure set, so it retries with backoff before
 * being dead-lettered. A missing transport is often a window rather than a verdict — a rolling
 * deploy where some instances still have it, or a configuration an operator is about to correct —
 * and retrying costs nothing but a log line, whereas giving up at once discards a message the next
 * minute could have delivered. Either way it ends visibly: in the dead-letter table once the
 * attempts are spent, never as a row quietly marked sent.
 */
public class UnreachableDestinationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UnreachableDestinationException(String type, int version, String destination) {
    super(
        "outbox row for "
            + type
            + " v"
            + version
            + " is destined for '"
            + destination
            + "' but the active OutboxDispatcher cannot reach an external target. The destination"
            + " was resolved when the row was written, so delivering it in process would archive an"
            + " event that never left the JVM. Restore the messaging starter that provides that"
            + " transport, or replay the row after removing the destination if it is genuinely"
            + " local now.");
  }
}
