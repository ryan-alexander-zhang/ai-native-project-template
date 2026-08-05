/**
 * What the refunds context publishes: one event, carrying the UUID rather than the legacy number.
 *
 * <p>The identity a contract is minted against outlives the table it came from, which is why {@code public_id} exists
 * before any consumer does.
 */
package com.example.samples.s25.refunds.api;
