package com.aipersimmon.ddd.core.model;

/**
 * An entity: a domain object with a distinct identity that runs through its lifecycle. Two entities
 * are equal when their identities are equal, not when their attribute values match.
 *
 * @param <ID> the identity type
 */
public interface Entity<ID> {

  /** The entity's identity. */
  ID id();
}
