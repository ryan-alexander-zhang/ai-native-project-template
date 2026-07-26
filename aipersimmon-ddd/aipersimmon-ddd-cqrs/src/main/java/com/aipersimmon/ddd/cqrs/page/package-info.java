/**
 * Cursor-first pagination value objects.
 *
 * <p>{@link com.aipersimmon.ddd.cqrs.page.Cursor} is an opaque position token that clients must not
 * construct or parse. {@link com.aipersimmon.ddd.cqrs.page.Slice} is the primary shape — items plus
 * an optional next cursor, with no total count — matching the direction large APIs have moved.
 * {@link com.aipersimmon.ddd.cqrs.page.Page} adds total counts for the offset-compatible case that
 * genuinely needs them.
 *
 * <p>These are the "list envelope" (a pagination shell), not a generic success envelope: a single
 * resource is still returned directly.
 *
 * <p>They live on the read side rather than in the web module because pagination is a property of a
 * query result, not of HTTP: an application-layer query handler must be able to return a {@code
 * Slice} without its module depending on the web tier. HTTP serialization of the opaque {@code
 * Cursor} stays in the web starter, so this tier remains free of Jackson.
 */
package com.aipersimmon.ddd.cqrs.page;
