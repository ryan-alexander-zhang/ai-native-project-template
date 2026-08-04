package com.example.samples.s09.ticketing.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/** A seat class's identity — its name, because that is what the business calls it. */
@ValueObject
public record SeatClassId(String value) implements Identifier {

  public SeatClassId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("seat class must not be blank");
    }
  }
}
