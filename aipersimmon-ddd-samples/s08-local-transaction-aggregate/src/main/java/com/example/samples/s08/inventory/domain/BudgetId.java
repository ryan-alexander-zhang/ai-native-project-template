package com.example.samples.s08.inventory.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/** The budget's identity. There is one per warehouse. */
@ValueObject
public record BudgetId(String value) implements Identifier {

  public BudgetId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("budget id must not be blank");
    }
  }
}
