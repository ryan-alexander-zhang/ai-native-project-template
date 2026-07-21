/**
 * Redis-backed implementations of the web-layer stateful SPIs ({@code IdempotencyStore}, {@code
 * ReplayGuard}, {@code RateLimiter}). Auto-wired when a {@code StringRedisTemplate} is present,
 * replacing the in-memory defaults with shared state that uses Redis native TTL and atomic {@code
 * INCR}. This is the recommended backend for rate limiting under concurrency.
 */
package com.aipersimmon.ddd.web.store.redis;
