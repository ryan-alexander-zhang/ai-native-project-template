package com.example.samples.s28.reconciliation.infrastructure;

import java.time.Instant;

/** What the progress table has, on its way to becoming an {@code ExportProgress}. */
public class ProgressReading {

  private long rowsDone;
  private Long rowsTotal;
  private Instant updatedAt;

  public long getRowsDone() {
    return rowsDone;
  }

  public void setRowsDone(long rowsDone) {
    this.rowsDone = rowsDone;
  }

  public Long getRowsTotal() {
    return rowsTotal;
  }

  public void setRowsTotal(Long rowsTotal) {
    this.rowsTotal = rowsTotal;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
