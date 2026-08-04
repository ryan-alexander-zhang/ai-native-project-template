package com.example.samples.s22.inventory.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;

/** You cannot reserve what is not there. */
record EnoughStock(Sku sku, int available, int wanted) implements Invariant {

  @Override
  public boolean isBroken() {
    return wanted > available;
  }

  @Override
  public String message() {
    return "sku " + sku.value() + " has " + available + " available, " + wanted + " wanted";
  }

  @Override
  public ErrorCode errorCode() {
    return InventoryErrorCode.NOT_ENOUGH_STOCK;
  }
}
