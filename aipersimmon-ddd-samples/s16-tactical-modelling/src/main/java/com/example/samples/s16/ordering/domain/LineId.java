package com.example.samples.s16.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/** A line's identity. A line is an entity here, so it needs one of its own. */
@ValueObject
public record LineId(String value) implements Identifier {

  public LineId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("line id must not be blank");
    }
  }
}
