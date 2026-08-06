/**
 * MyBatis-Plus-backed implementations of the web-layer stateful SPIs ({@code IdempotencyStore},
 * {@code ReplayGuard}, {@code RateLimiter}). Auto-wired once MyBatis-Plus has produced a {@code
 * SqlSessionFactory}, replacing the in-memory defaults so state is shared across instances. Uses
 * MyBatis-Plus annotations, not a JPA {@code @Entity}, and registers only its own mappers, so it
 * never affects a consumer's entity scanning or {@code @MapperScan}. The table DDL ships as Flyway
 * migrations in {@code aipersimmon-ddd-web} and is never auto-run.
 */
package com.aipersimmon.ddd.web.store.mybatisplus;
