package com.aipersimmon.ddd.operationlog.model;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import java.util.Objects;

/**
 * A sanitized description of a failure safe to store in a business-readable log: a stable code, a
 * coarse category, and a short safe summary. Never carries a stack trace, SQL, an original
 * exception message, or any transport payload.
 */
@ValueObject
public record ClassifiedFailure(String code, String category, String safeSummary) {

  /**
   * @throws NullPointerException if {@code code} or {@code category} is null
   */
  public ClassifiedFailure {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(category, "category");
  }
}
