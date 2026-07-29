package com.aipersimmon.ddd.web.spi;

/**
 * The outcome of trying to claim an {@link IdempotencyKey} for execution — the four states a key
 * can be in when a request arrives, made explicit so the caller cannot conflate them.
 *
 * <p>Claiming happens <em>before</em> the request executes. That ordering is the whole point: a
 * store that only records the response afterwards cannot stop two concurrent first attempts from
 * both running, and both running is exactly the double charge an idempotency key exists to prevent.
 */
public sealed interface IdempotencyClaim {

  /** The caller holds the claim and must execute, then {@code complete} or {@code abandon} it. */
  record Won() implements IdempotencyClaim {}

  /**
   * Another attempt holds the claim and has not finished. There is no outcome to replay yet and
   * executing would duplicate the side effect, so the honest answer is "ask again shortly" — the
   * usual mapping is {@code 409 Conflict} with {@code Retry-After}.
   */
  record InProgress() implements IdempotencyClaim {}

  /** A completed outcome exists; return it verbatim rather than executing again. */
  record Replay(StoredResponse response) implements IdempotencyClaim {
    public Replay {
      if (response == null) {
        throw new IllegalArgumentException("response must not be null");
      }
    }
  }

  /**
   * The key is in use for a different request than the one presenting it now (the fingerprints
   * differ). Neither answer is right — executing breaks the caller's own assumption that the key
   * names one operation, replaying returns an outcome for something they did not ask for — so this
   * is a refusal, conventionally {@code 422 Unprocessable Content}.
   */
  record Mismatch() implements IdempotencyClaim {}
}
