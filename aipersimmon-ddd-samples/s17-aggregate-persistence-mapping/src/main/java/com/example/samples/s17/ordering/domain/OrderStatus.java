package com.example.samples.s17.ordering.domain;

/** Persisted as its name, not its ordinal: inserting a new constant must not renumber the stored rows. */
public enum OrderStatus {
  DRAFT,
  PLACED,
  CANCELLED
}
