/**
 * Cursor-first pagination value objects.
 *
 * <p>{@link com.aipersimmon.ddd.web.page.Cursor} is an opaque position token that clients must not
 * construct or parse. {@link com.aipersimmon.ddd.web.page.Slice} is the primary shape — items plus
 * an optional next cursor, with no total count — matching the direction large APIs have moved.
 * {@link com.aipersimmon.ddd.web.page.Page} adds total counts for the offset-compatible case that
 * genuinely needs them.
 *
 * <p>These are the "list envelope" (a pagination shell), not a generic success envelope: a single
 * resource is still returned directly. Serialization lives in the starter, so this tier stays free
 * of Jackson.
 */
package com.aipersimmon.ddd.web.page;
