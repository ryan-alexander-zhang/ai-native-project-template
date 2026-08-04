package com.example.samples.s28.reconciliation.domain;

/** Open for chunks, or closed one of two ways. */
public enum ImportStatus {
  OPEN,
  COMPLETED,
  ABANDONED;

  public boolean isClosed() {
    return this != OPEN;
  }
}
