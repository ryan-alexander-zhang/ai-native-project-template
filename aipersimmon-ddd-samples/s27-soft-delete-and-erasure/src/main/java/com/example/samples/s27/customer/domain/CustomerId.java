package com.example.samples.s27.customer.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/**
 * A customer's identity, and the one thing about a customer an erasure does <strong>not</strong> remove.
 *
 * <p>Worth being explicit, because it is the question a lawyer asks second: the id survives an erasure, so
 * the row survives, so "this person existed and was erased" remains provable. Under most regimes that is
 * both allowed and required — the erasure obligation covers the personal data, and the record that the
 * obligation was discharged is not itself personal data as long as it cannot be tied back to a person
 * through anything left in the system.
 *
 * <p>Which is a property of the id: it is a surrogate the service minted, not a natural key. Had the
 * customer's email been the primary key, erasure would mean deleting the row, and with it every audit and
 * ledger reference to it — the obligation and the record of discharging it would be in direct conflict.
 * That is the argument for surrogate keys that survives contact with compliance.
 */
@ValueObject
public record CustomerId(String value) implements Identifier {

  public CustomerId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("customer id must not be blank");
    }
  }
}
