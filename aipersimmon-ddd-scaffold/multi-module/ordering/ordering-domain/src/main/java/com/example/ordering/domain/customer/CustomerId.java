package com.example.ordering.domain.customer;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.model.Identifier;

/** Identity of a {@link Customer}; also how other aggregates refer to a customer. */
public record CustomerId(String value) implements Identifier {

  public CustomerId {
    if (value == null || value.isBlank()) {
      throw new DomainException("customer id required");
    }
  }
}
