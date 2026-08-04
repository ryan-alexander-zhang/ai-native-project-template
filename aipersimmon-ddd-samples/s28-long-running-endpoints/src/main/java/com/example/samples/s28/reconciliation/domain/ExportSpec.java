package com.example.samples.s28.reconciliation.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;

/**
 * What was asked for: a period and a format.
 *
 * <p>Immutable, and the job refuses to have it changed. That refusal is the asynchronous contract's
 * equivalent of the idempotency store's {@code Mismatch} verdict — {@code PUT}ting the same job id with a
 * different period is neither a duplicate (the caller asked for something else) nor a new job (the id is
 * taken), so the only honest answer is to refuse.
 *
 * @param period the settlement period, {@code yyyy-MM}
 * @param format how the artifact is written
 */
@ValueObject
public record ExportSpec(String period, ExportFormat format) {

  public ExportSpec {
    if (period == null || !period.matches("\\d{4}-\\d{2}")) {
      throw new IllegalArgumentException("period must be yyyy-MM, was " + period);
    }
    if (format == null) {
      throw new IllegalArgumentException("format required");
    }
  }

  /** One format, and the enum exists so the column is not free text and a second one is additive. */
  public enum ExportFormat {
    CSV
  }
}
