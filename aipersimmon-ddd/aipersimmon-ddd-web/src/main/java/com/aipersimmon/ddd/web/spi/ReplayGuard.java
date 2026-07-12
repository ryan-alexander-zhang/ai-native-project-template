package com.aipersimmon.ddd.web.spi;

import java.time.Duration;

/**
 * Single-use nonce tracking for replay protection: records a nonce the first time
 * it is seen and reports any later reuse, so a captured, already-signed request
 * cannot be replayed within the retention window. This is the stronger tier of
 * replay defence; timestamp-tolerance checking happens in the filter, and the
 * signature is verified by {@link RequestSignatureVerifier}.
 *
 * <p>Distinct from {@link IdempotencyStore}: that makes an authorised retry safe;
 * this rejects a malicious replay.
 */
public interface ReplayGuard {

    /**
     * Records {@code nonce} if unseen and reports whether it had been seen before.
     *
     * @param nonce the nonce from the request
     * @param ttl   how long to remember the nonce (should cover the timestamp window)
     * @return {@code true} if the nonce was already seen (reject as replay),
     *         {@code false} if this is its first use (accept)
     */
    boolean seenBefore(String nonce, Duration ttl);
}
