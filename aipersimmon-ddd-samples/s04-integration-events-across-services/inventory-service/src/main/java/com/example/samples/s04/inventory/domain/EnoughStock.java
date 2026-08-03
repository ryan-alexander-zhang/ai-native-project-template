package com.example.samples.s04.inventory.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;

/** The rule a redelivery must not be allowed to break twice. */
record EnoughStock(Sku sku, int available, int requested) implements Invariant {

  @Override
  public boolean isBroken() {
    return requested > available;
  }

  @Override
  public String message() {
    return "cannot reserve " + requested + " of " + sku.value() + ", only " + available + " available";
  }

  @Override
  public ErrorCode errorCode() {
    return InventoryErrorCode.INSUFFICIENT_STOCK;
  }
}
