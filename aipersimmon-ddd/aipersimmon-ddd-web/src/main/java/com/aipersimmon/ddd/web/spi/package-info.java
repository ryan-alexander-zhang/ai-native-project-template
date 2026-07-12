/**
 * The cross-cutting SPIs whose state a backend must hold.
 *
 * <p>Idempotency ({@link com.aipersimmon.ddd.web.spi.IdempotencyStore}), replay
 * protection ({@link com.aipersimmon.ddd.web.spi.ReplayGuard}) and rate limiting
 * ({@link com.aipersimmon.ddd.web.spi.RateLimiter}) all need short-lived, keyed
 * state with a TTL, so they share one pluggable store family — mirroring how the
 * outbox splits its contract from its storage backends. Request-signature
 * verification ({@link com.aipersimmon.ddd.web.spi.RequestSignatureVerifier}) is
 * the stateless half of replay protection.
 *
 * <p>A starter ships in-memory defaults (single-node/dev only); Redis and JDBC
 * modules provide the production implementations, selected by classpath.
 */
package com.aipersimmon.ddd.web.spi;
