package com.example.samples.s10.points.domain;

/** What a TCC Confirm or Cancel did. */
public enum SettleOutcome {
  SETTLED,
  ALREADY_SETTLED,
  ALREADY_CANCELLED,
  /**
   * There was no reservation. For Cancel this is Seata's <em>empty rollback</em> and it is ordinary; for
   * Confirm it never is, and the caller says so.
   */
  NOTHING_TO_SETTLE
}
