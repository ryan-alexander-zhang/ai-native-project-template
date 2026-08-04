package com.example.samples.s28.reconciliation.domain;

/**
 * Five states, three of them terminal.
 *
 * <p>There is no {@code CANCELLING}. The temptation is real — the worker may take a while to notice — but a
 * sixth state would be a state nobody can act on: a client polling {@code CANCELLING} learns nothing it
 * could not read from {@code RUNNING} plus the cancellation it just requested, and the worker does not
 * consult it. The request lives on the job as a flag instead, which is what it is.
 */
public enum ExportStatus {
  QUEUED,
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCELLED;

  public boolean isTerminal() {
    return this == SUCCEEDED || this == FAILED || this == CANCELLED;
  }
}
