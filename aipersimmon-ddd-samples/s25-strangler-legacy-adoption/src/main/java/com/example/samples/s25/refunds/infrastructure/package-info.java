/**
 * Persistence over {@code legacy_refunds} — a table with a {@code BIGSERIAL} key, three columns the aggregate does not
 * own, and a foreign key into a table that has not been extracted.
 *
 * <p>All three are accommodated rather than fixed, because fixing them is a separate migration and doing both at once
 * means neither can be reverted.
 */
package com.example.samples.s25.refunds.infrastructure;
