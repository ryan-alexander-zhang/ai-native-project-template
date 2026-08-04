package com.example.samples.s09.ticketing.domain;

/**
 * What happened when a seat was asked for. Returned rather than thrown: two of the three are ordinary
 * facts the coordinator has to act on, and an exception would give it nothing to decide with.
 */
public enum HoldOutcome {

  /** A seat was taken for this order. */
  HELD,

  /** This order already held one — a redelivered effect, absorbed. */
  ALREADY_HELD,

  /** None left. A business answer, and the reason the flow has a compensation path at all. */
  SOLD_OUT
}
