package com.example.samples.s03.rewards.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/** A coupon's identity. */
@ValueObject
public record CouponId(String value) implements Identifier {

  public CouponId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("coupon id must not be blank");
    }
  }
}
