package com.example.samples.s09.ticketing.domain;

/** What happened when money was asked for. Same reasoning as {@link HoldOutcome}. */
public enum DebitOutcome {

  /** The balance covered it. */
  CHARGED,

  /** This reference had already been applied — a redelivered effect, absorbed. */
  ALREADY_APPLIED,

  /** Not enough money. A business answer, not a fault. */
  INSUFFICIENT_FUNDS
}
