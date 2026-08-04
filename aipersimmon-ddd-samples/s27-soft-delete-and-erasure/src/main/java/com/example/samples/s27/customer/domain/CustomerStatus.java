package com.example.samples.s27.customer.domain;

/**
 * The customer's lifecycle, which is the <em>domain</em> kind of deletion.
 *
 * <p>Two states and a reason column is all it takes to be a better answer than a boolean: {@code CLOSED}
 * can be explained, can be reversed, and is a legitimate thing to query for. A {@code deleted} flag can do
 * none of those, which is the test for whether a soft delete is really domain state — if somebody will ask
 * "why", or ask for it back, or ask for a list of them, it is a state and not a switch.
 */
public enum CustomerStatus {
  ACTIVE,
  CLOSED
}
