package com.aipersimmon.ddd.archunit.fixture.good.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import java.util.Objects;

/**
 * A value object written as a class rather than a record, so it has to declare its value equality
 * by hand — the compliant non-record path of {@code valueObjectsShouldDeclareValueEquality}. Its
 * neighbours {@link GoodMoney} and {@link GoodSku} cover the record path, where the compiler
 * generates both methods.
 */
@ValueObject
public final class GoodQuantity {

  private final int units;

  public GoodQuantity(int units) {
    this.units = units;
  }

  public int units() {
    return units;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof GoodQuantity quantity && quantity.units == units;
  }

  @Override
  public int hashCode() {
    return Objects.hash(units);
  }
}
