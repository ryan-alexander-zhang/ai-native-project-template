package com.example.samples.s21.inventory.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;

/** The rule a duplicate — including one created by publishing two revisions of one fact — must not break. */
record EnoughStock(StockLocation location, int available, int requested) implements Invariant {

  @Override
  public boolean isBroken() {
    return requested > available;
  }

  @Override
  public String message() {
    return "cannot reserve "
        + requested
        + " at "
        + location.value()
        + ", only "
        + available
        + " available";
  }

  @Override
  public ErrorCode errorCode() {
    return InventoryErrorCode.INSUFFICIENT_STOCK;
  }
}
