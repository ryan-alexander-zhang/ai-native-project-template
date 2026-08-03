package com.example.samples.s08.inventory.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;

/** Reads only this stock item's own data, which is why it can be its invariant. */
record EnoughStock(Sku sku, int available, int wanted) implements Invariant {

  @Override
  public boolean isBroken() {
    return wanted > available;
  }

  @Override
  public String message() {
    return "sku " + sku.value() + " has " + available + " available but " + wanted + " was wanted";
  }

  @Override
  public ErrorCode errorCode() {
    return InventoryErrorCode.NOT_ENOUGH_STOCK;
  }
}
