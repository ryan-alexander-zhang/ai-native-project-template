package com.example.samples.s27.customer.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;

/**
 * An email address, validated loosely on purpose.
 *
 * <p>Loosely because an erasure has to be able to write one. The tombstone this sample writes —
 * {@code erased+<id>@invalid} — is not a deliverable address and never will be, and a value object strict
 * enough to reject it would force the erasure to bypass the model to do its job. A tombstone that cannot be
 * expressed in the domain's own vocabulary is a tombstone written with raw SQL, and then the aggregate's
 * invariants stop applying to the state it is left in.
 *
 * <p>{@code .invalid} is reserved by RFC 2606 precisely so that it can never resolve, which is what makes it
 * a safe tombstone: no future code can accidentally send to it, and no operator can mistake it for a real
 * address that happens to bounce.
 */
@ValueObject
public record EmailAddress(String value) {

  public EmailAddress {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("email must not be blank");
    }
    if (value.indexOf('@') <= 0 || value.endsWith("@")) {
      throw new IllegalArgumentException("email must contain a local part and a domain: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
