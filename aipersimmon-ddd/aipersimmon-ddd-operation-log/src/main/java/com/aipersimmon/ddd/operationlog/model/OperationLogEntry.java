package com.aipersimmon.ddd.operationlog.model;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import java.util.List;
import java.util.Objects;

/**
 * The immutable, frozen operation-log record — the persisted truth produced by {@code
 * OperationLogs} after normalize / validate / redact / freeze. Fields are grouped into
 * sub-value-objects ({@link OperationResult}, {@link EntryTimes}, {@link TemplateRef}) to keep the
 * shape cohesive.
 *
 * <p>Deliberately carries no {@code requestId} / {@code traceId}: correlation to technical logs and
 * spans is via {@code recordId} + {@link Causality#correlationId()} and the ambient OTel context.
 */
@ValueObject
public record OperationLogEntry(
    String recordId,
    String source,
    String tenantId,
    String idempotencyKey,
    String operationCode,
    Actor actor,
    Target target,
    OperationResult result,
    String summary,
    List<OperationChange> changes,
    List<OperationDetail> details,
    ClassifiedFailure failure,
    Causality causality,
    EntryTimes times,
    TemplateRef templateRef,
    int schemaVersion) {

  /**
   * Freezes the collections and validates the required identity/result fields.
   *
   * @throws NullPointerException if a required field is null
   */
  public OperationLogEntry {
    Objects.requireNonNull(recordId, "recordId");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(operationCode, "operationCode");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(causality, "causality");
    Objects.requireNonNull(times, "times");
    changes = changes == null ? List.of() : List.copyOf(changes);
    details = details == null ? List.of() : List.copyOf(details);
  }

  /** The business outcome dimension. */
  public Outcome outcome() {
    return result.outcome();
  }

  /** The transaction completion dimension. */
  public Completion completion() {
    return result.completion();
  }
}
