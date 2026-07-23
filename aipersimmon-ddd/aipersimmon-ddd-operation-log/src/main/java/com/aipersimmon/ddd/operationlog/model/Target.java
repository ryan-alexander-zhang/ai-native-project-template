package com.aipersimmon.ddd.operationlog.model;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import java.util.Objects;

/**
 * The single primary business object an operation acts on. v1 records exactly one target; sensitive
 * natural identifiers should be replaced by a stable surrogate before recording.
 */
@ValueObject
public record Target(String type, String id, String displayName) {

  /**
   * @throws NullPointerException if {@code type} or {@code id} is null
   */
  public Target {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(id, "id");
  }

  /** A target with no display name. */
  public static Target of(String type, String id) {
    return new Target(type, id, null);
  }
}
