package com.example.samples.s10.banking.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/** An account's identity. */
@ValueObject
public record AccountId(String value) implements Identifier {

  public AccountId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("account id must not be blank");
    }
  }
}
