package com.example.samples.s16.ordering.domain;

/** A customer's loyalty tier, as far as the ordering context is concerned. */
public enum LoyaltyTier {
  STANDARD(0),
  SILVER(5),
  GOLD(10);

  private final int discountPercent;

  LoyaltyTier(int discountPercent) {
    this.discountPercent = discountPercent;
  }

  int discountPercent() {
    return discountPercent;
  }
}
