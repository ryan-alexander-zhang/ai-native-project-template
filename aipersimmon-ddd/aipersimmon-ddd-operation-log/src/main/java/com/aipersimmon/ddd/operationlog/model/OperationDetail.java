package com.aipersimmon.ddd.operationlog.model;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import java.util.Objects;

/**
 * A single bounded, allowlisted name/value fact attached to an operation. Not an arbitrary metadata
 * map — the set is explicit and size-budgeted.
 */
@ValueObject
public record OperationDetail(String name, String value) {

  /**
   * @throws NullPointerException if {@code name} is null
   */
  public OperationDetail {
    Objects.requireNonNull(name, "name");
  }
}
