package com.example.samples.s09.ticketing.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/** A wallet's identity: the customer it belongs to. */
@ValueObject
public record WalletId(String value) implements Identifier {

  public WalletId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("wallet id must not be blank");
    }
  }
}
