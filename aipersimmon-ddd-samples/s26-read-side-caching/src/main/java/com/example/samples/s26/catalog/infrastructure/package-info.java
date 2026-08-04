/**
 * The adapters: MyBatis-Plus for everything in Postgres, and one class that speaks Redis.
 *
 * <p>Only {@code RedisQueryCache} knows what the cache is made of. Everything about <em>how</em> the cache
 * behaves — keys, expiry, single flight, when entries are dropped — is in {@code application}, so this
 * package could be replaced by a {@code ConcurrentHashMap} for a single-instance deployment without any
 * policy moving with it.
 */
package com.example.samples.s26.catalog.infrastructure;
