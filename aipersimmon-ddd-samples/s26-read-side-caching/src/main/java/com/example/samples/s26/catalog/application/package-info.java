/**
 * The use cases, and the cache.
 *
 * <p>The cache lives here rather than in {@code infrastructure} because <em>what</em> is cached, under
 * what key, for how long, and when it is dropped are application decisions. Only the thing that speaks
 * Redis is infrastructure, behind {@link
 * com.example.samples.s26.catalog.application.QueryCache}. The split matters the day someone swaps
 * Redis for a local map: the policy — tenant-scoped keys, jittered expiry, single flight,
 * evict-after-commit — must survive that swap unchanged, and it does only if it never knew which store
 * it was talking to.
 */
package com.example.samples.s26.catalog.application;
