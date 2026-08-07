package com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;

/**
 * Violates {@code valueObjectsShouldDeclareValueEquality}: a {@code @ValueObject} that is neither a
 * record nor an enum and declares no {@code equals}/{@code hashCode}, so two instances with the
 * same attributes are unequal.
 *
 * <p>Deliberately immutable — every field is {@code final} — so it <em>passes</em> {@code
 * valueObjectsShouldBeImmutable} and fails only the equality rule. That is the gap the new rule
 * exists to close: without it, this type carries the marker, satisfies the only check the marker
 * used to imply, and still behaves as if it had identity.
 */
@ValueObject
public final class BadValueObjectComparedByReference {

  private final long amountMinor;
  private final String currency;

  public BadValueObjectComparedByReference(long amountMinor, String currency) {
    this.amountMinor = amountMinor;
    this.currency = currency;
  }

  public long amountMinor() {
    return amountMinor;
  }

  public String currency() {
    return currency;
  }
}
