package com.example.samples.s23.billing.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/** An invoice's identity. */
@ValueObject
public record InvoiceId(String value) implements Identifier {

  public InvoiceId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("invoice id must not be blank");
    }
  }
}
