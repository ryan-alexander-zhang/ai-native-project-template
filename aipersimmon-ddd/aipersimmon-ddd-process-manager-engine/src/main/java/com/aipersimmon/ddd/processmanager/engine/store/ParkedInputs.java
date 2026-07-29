package com.aipersimmon.ddd.processmanager.engine.store;

/**
 * The durable identity of a parked input's replay.
 *
 * <p>A replay cannot reuse the parked input's own message id — {@code UNIQUE(instance_id,
 * input_message_id)} would make it a duplicate no-op, which is exactly what the park row already is
 * — so it runs under a prefixed derivation of it. That derivation is a persisted convention (replay
 * transitions in existing databases carry it), shared by the runtime, which must recognise a replay
 * to avoid parking one, and the parked-input worker, which mints it.
 *
 * <p>It assumes no business input ever arrives with a message id that already starts with the
 * prefix; transport message ids are broker- or UUID-shaped, so this holds in practice.
 */
public final class ParkedInputs {

  /** The prefix that marks a message id as the replay of a parked input. */
  public static final String REPLAY_PREFIX = "parked:";

  private ParkedInputs() {}

  /** The message id a parked input is replayed under. */
  public static String replayIdFor(String inputMessageId) {
    return REPLAY_PREFIX + inputMessageId;
  }

  /** Whether this message id is the replay of a parked input rather than a fresh arrival. */
  public static boolean isReplayId(String messageId) {
    return messageId != null && messageId.startsWith(REPLAY_PREFIX);
  }

  /** The parked input a replay id was derived from. */
  public static String originalIdOf(String replayId) {
    return replayId.substring(REPLAY_PREFIX.length());
  }
}
