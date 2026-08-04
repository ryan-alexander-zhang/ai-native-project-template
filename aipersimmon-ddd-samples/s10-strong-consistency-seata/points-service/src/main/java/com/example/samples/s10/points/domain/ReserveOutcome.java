package com.example.samples.s10.points.domain;

/** What a TCC Try did. */
public enum ReserveOutcome {
  RESERVED,
  /** Try delivered twice. Success. */
  ALREADY_RESERVED,
  /** Already confirmed. Also success — the caller is behind, not wrong. */
  ALREADY_SETTLED,
  /**
   * Cancel got here first, so this Try must not proceed. Seata's "suspension" hazard, refused. Reserving
   * now would freeze points that no Confirm and no Cancel will ever come for.
   */
  CANCELLED_BEFORE_RESERVED
}
