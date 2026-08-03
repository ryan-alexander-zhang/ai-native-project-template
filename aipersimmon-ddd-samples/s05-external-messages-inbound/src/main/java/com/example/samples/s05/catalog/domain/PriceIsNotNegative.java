package com.example.samples.s05.catalog.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;

/**
 * The one rule this mirror keeps of its own accord.
 *
 * <p>A mirror is not a rubber stamp. Upstream can send anything, and a negative price is a fact this
 * context refuses to hold whatever the ERP believes — which is the difference between translating a
 * message and obeying it.
 */
record PriceIsNotNegative(long priceCents) implements Invariant {

  @Override
  public boolean isBroken() {
    return priceCents < 0;
  }

  @Override
  public String message() {
    return "a price must not be negative, was " + priceCents;
  }

  @Override
  public ErrorCode errorCode() {
    return CatalogErrorCode.NEGATIVE_PRICE;
  }
}
