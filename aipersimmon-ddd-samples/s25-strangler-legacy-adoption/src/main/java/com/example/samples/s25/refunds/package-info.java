/**
 * The refunds context: the first aggregate out of the monolith.
 *
 * <p>Chosen by measurement rather than by taste — one writer of substance in the legacy service, and three real rules
 * (one of which the monolith did not have). {@code LegacyFanInTest} computes the criterion; the same computation is what
 * later says the migration is finished.
 *
 * <p>It lives over {@code legacy_refunds} — a {@code BIGSERIAL} key, three columns it does not own, and a foreign key
 * into a table nobody has extracted. Every one of those is accommodated rather than fixed, because fixing them is a
 * separate migration and doing two at once means neither can be reverted.
 */
package com.example.samples.s25.refunds;
