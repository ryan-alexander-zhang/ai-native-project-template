package com.example.samples.s10.points.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/** A points account's identity: the customer, because points belong to a customer and not to a card. */
@ValueObject
public record PointsAccountId(String value) implements Identifier {

  public PointsAccountId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("points account id must not be blank");
    }
  }
}
