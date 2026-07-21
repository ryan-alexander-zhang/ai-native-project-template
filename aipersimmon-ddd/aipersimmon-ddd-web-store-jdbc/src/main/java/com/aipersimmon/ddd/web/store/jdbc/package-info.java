/**
 * JdbcTemplate-backed implementations of the web-layer stateful SPIs ({@code IdempotencyStore},
 * {@code ReplayGuard}, {@code RateLimiter}). Auto-wired when a {@code JdbcTemplate} is present,
 * replacing the in-memory defaults so state is shared across instances. The consumer owns the table
 * DDL (a documented, non-auto-run sample ships under {@code META-INF/aipersimmon-ddd}).
 */
package com.aipersimmon.ddd.web.store.jdbc;
