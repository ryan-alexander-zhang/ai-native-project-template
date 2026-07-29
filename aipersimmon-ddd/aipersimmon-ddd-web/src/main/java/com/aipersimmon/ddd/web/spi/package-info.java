/**
 * The cross-cutting SPIs whose state a backend must hold.
 *
 * <p>Idempotency ({@link com.aipersimmon.ddd.web.spi.IdempotencyStore}), replay protection ({@link
 * com.aipersimmon.ddd.web.spi.ReplayGuard}) and rate limiting ({@link
 * com.aipersimmon.ddd.web.spi.RateLimiter}) all need short-lived, keyed state with a TTL, so they
 * share one pluggable store family — mirroring how the outbox splits its contract from its storage
 * backends. Request-signature verification ({@link
 * com.aipersimmon.ddd.web.spi.RequestSignatureVerifier}) is the stateless half of replay
 * protection.
 *
 * <p>A starter ships in-memory defaults (single-node/dev only); Redis and JDBC modules provide the
 * production implementations, selected by classpath.
 *
 * <p>Idempotency is the one that is not merely keyed state: it is a three-call lifecycle ({@code
 * claim} / {@code complete} / {@code abandon}) because the key must be taken <em>before</em> the
 * request executes. Recording the finished response cannot make a write execute once — two
 * concurrent first attempts both find nothing stored and both run. {@link
 * com.aipersimmon.ddd.web.spi.IdempotencyKey} carries the caller as part of that key's identity,
 * and {@link com.aipersimmon.ddd.web.spi.IdempotencyPrincipalResolver} is how a deployment says who
 * the caller is.
 */
package com.aipersimmon.ddd.web.spi;
