package com.aipersimmon.ddd.operationlog.model;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import java.util.Objects;

/**
 * An explicit, allowlisted before/after change of a single field. The structured fact; a rendered
 * summary is only its presentation snapshot. Values are already frozen and redacted.
 */
@ValueObject
public record OperationChange(String field, String label, String before, String after) {

  /**
   * @throws NullPointerException if {@code field} is null
   */
  public OperationChange {
    Objects.requireNonNull(field, "field");
  }
}
