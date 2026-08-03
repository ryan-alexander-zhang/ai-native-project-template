package com.example.samples.s17.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/** A line's identity. Lines are entities here, which is what makes the child-write strategy a real
 * decision — see {@code MyBatisOrders#saveChildren}. */
@ValueObject
public record LineId(String value) implements Identifier {

  public LineId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("line id must not be blank");
    }
  }
}
