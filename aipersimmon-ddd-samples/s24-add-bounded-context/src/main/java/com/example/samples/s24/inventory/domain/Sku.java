package com.example.samples.s24.inventory.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/**
 * A stock-keeping unit, and a small deliberate inconsistency the sample would rather show than hide.
 *
 * <p>It is <strong>not</strong> published: {@code inventory.api} has no {@code Sku}. So ordering holds skus as plain
 * strings while it holds coupon codes as a typed {@code CouponCode}, which is inconsistent — and it is the inconsistency
 * a real service has. The typed reference was worth introducing when the new context was added, because that was the
 * moment somebody was thinking about the boundary; nobody has been back to inventory since it was written.
 *
 * <p>Which is the honest lesson about publishing identifier types: it is nearly always right, and it usually happens
 * only for the boundary somebody is currently paying attention to.
 */
@ValueObject
public record Sku(String value) implements Identifier {

  public Sku {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("sku must not be blank");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
