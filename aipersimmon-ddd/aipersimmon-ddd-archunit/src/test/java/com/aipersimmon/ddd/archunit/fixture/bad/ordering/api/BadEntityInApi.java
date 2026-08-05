package com.aipersimmon.ddd.archunit.fixture.bad.ordering.api;

import com.aipersimmon.ddd.core.annotation.Entity;

/**
 * The control on the published-value-object allowance: {@code ..api..} was opened to
 * {@code @ValueObject} only. An {@code @Entity} there is still a violation — it has identity, a
 * lifecycle and invariants, and publishing it makes a shared model out of what a bounded context
 * exists to keep private.
 */
@Entity
public class BadEntityInApi {

  private final String id;

  public BadEntityInApi(String id) {
    this.id = id;
  }

  public String id() {
    return id;
  }
}
