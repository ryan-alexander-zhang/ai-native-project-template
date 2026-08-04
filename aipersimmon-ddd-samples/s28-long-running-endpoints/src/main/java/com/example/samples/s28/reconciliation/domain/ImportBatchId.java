package com.example.samples.s28.reconciliation.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/** A batch's identity, supplied by the uploader for the reasons set out on {@link ExportJobId}. */
@ValueObject
public record ImportBatchId(String value) implements Identifier {

  public ImportBatchId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("import batch id must not be blank");
    }
    if (value.length() > 64) {
      throw new IllegalArgumentException("import batch id must be at most 64 characters");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
