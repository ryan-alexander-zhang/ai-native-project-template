package com.aipersimmon.ddd.web.spi;

/**
 * Everything that identifies one idempotent attempt.
 *
 * <p>The client supplies only {@link #key()}. On its own that is not an identity: a key is a value
 * one caller invents for one request, so the store must qualify it with who is calling and under
 * which tenant. Without the caller, presenting a key someone else used returns their stored
 * response — the response body of another user's request, served from a cache. So the triple {@code
 * (tenant, principal, key)} is the identity, and two callers may use the same key without ever
 * meeting.
 *
 * <p>{@link #fingerprint()} is not part of the identity but is compared against it: it summarises
 * what was requested, so reusing a key for a genuinely different request is answerable with a
 * refusal instead of the wrong stored outcome.
 *
 * @param tenant the owning tenant; the sentinel when multi-tenancy is off
 * @param principal the authenticated caller, or empty when the endpoint is unauthenticated
 * @param key the client-supplied key
 * @param fingerprint a digest of what was requested, compared on lookup
 */
public record IdempotencyKey(String tenant, String principal, String key, String fingerprint) {

  /** The longest client-supplied key accepted; the column is sized for it. */
  public static final int MAX_KEY_LENGTH = 255;

  public IdempotencyKey {
    if (tenant == null || tenant.isBlank()) {
      throw new IllegalArgumentException("tenant must not be blank");
    }
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("key must not be blank");
    }
    if (key.length() > MAX_KEY_LENGTH) {
      throw new IllegalArgumentException(
          "key must be at most " + MAX_KEY_LENGTH + " characters, was " + key.length());
    }
    principal = principal == null ? "" : principal;
    fingerprint = fingerprint == null ? "" : fingerprint;
  }
}
