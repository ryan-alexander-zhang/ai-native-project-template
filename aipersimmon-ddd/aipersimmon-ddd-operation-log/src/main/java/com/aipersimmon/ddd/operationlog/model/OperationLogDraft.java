package com.aipersimmon.ddd.operationlog.model;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A bounded, pre-freeze description of one operation to record, produced by a definition's {@code
 * complete}/{@code failed} or by a direct-API caller. {@code OperationLogs} still normalizes,
 * validates, redacts, and freezes it into an {@link OperationLogEntry}; the draft never carries a
 * whole aggregate or mutable entity.
 */
@ValueObject
public record OperationLogDraft(
    OperationLogInvocation invocation,
    String operationCode,
    Target target,
    OperationResult result,
    String summary,
    List<OperationChange> changes,
    List<OperationDetail> details,
    ClassifiedFailure failure,
    String idempotencyKey,
    TemplateRef templateRef) {

  /**
   * @throws NullPointerException if invocation, operationCode, target, or result is null
   */
  public OperationLogDraft {
    Objects.requireNonNull(invocation, "invocation");
    Objects.requireNonNull(operationCode, "operationCode");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(result, "result");
    changes = changes == null ? List.of() : List.copyOf(changes);
    details = details == null ? List.of() : List.copyOf(details);
  }

  /** Start a draft bound to a trusted invocation. */
  public static Builder from(OperationLogInvocation invocation) {
    return new Builder(invocation);
  }

  /**
   * A copy with the result kind replaced. The failure interceptor uses this to stamp the
   * classifier-decided outcome and the transaction-derived completion onto a definition-built
   * draft.
   */
  public OperationLogDraft withResult(OperationResult result) {
    return new OperationLogDraft(
        invocation,
        operationCode,
        target,
        result,
        summary,
        changes,
        details,
        failure,
        idempotencyKey,
        templateRef);
  }

  /** Fluent builder for {@link OperationLogDraft}. */
  public static final class Builder {
    private final OperationLogInvocation invocation;
    private final List<OperationChange> changes = new ArrayList<>();
    private final List<OperationDetail> details = new ArrayList<>();
    private String operationCode;
    private Target target;
    private Outcome outcome;
    private Completion completion;
    private ClassifiedFailure failure;
    private String summary;
    private String idempotencyKey;
    private TemplateRef templateRef;

    private Builder(OperationLogInvocation invocation) {
      this.invocation = Objects.requireNonNull(invocation, "invocation");
    }

    /** The stable business operation code. */
    public Builder operation(String operationCode) {
      this.operationCode = operationCode;
      return this;
    }

    /** The primary target. */
    public Builder target(Target target) {
      this.target = target;
      return this;
    }

    /** The primary target from its parts. */
    public Builder target(String type, String id, String displayName) {
      this.target = new Target(type, id, displayName);
      return this;
    }

    /** Set the business outcome explicitly. */
    public Builder outcome(Outcome outcome) {
      this.outcome = outcome;
      return this;
    }

    /** Set the transaction completion explicitly (otherwise defaulted from the outcome). */
    public Builder completion(Completion completion) {
      this.completion = completion;
      return this;
    }

    /** Convenience for {@code outcome(SUCCEEDED)}. */
    public Builder succeeded() {
      this.outcome = Outcome.SUCCEEDED;
      return this;
    }

    /** Convenience for {@code outcome(REJECTED)}. */
    public Builder rejected() {
      this.outcome = Outcome.REJECTED;
      return this;
    }

    /** Convenience for {@code outcome(FAILED)} plus the sanitized failure. */
    public Builder failed(ClassifiedFailure failure) {
      this.outcome = Outcome.FAILED;
      this.failure = failure;
      return this;
    }

    /** Attach a sanitized failure without changing the outcome. */
    public Builder failure(ClassifiedFailure failure) {
      this.failure = failure;
      return this;
    }

    /** The rendered, human-readable summary snapshot. */
    public Builder summary(String summary) {
      this.summary = summary;
      return this;
    }

    /** Append an allowlisted field change. */
    public Builder change(String field, String label, String before, String after) {
      this.changes.add(new OperationChange(field, label, before, after));
      return this;
    }

    /** Append a bounded name/value detail. */
    public Builder detail(String name, String value) {
      this.details.add(new OperationDetail(name, value));
      return this;
    }

    /** A stable idempotency key (required only for retryable direct-API calls). */
    public Builder idempotencyKey(String idempotencyKey) {
      this.idempotencyKey = idempotencyKey;
      return this;
    }

    /** The template reference that produced the summary. */
    public Builder templateRef(String key, String version) {
      this.templateRef = TemplateRef.of(key, version);
      return this;
    }

    /**
     * Build the immutable draft.
     *
     * @throws NullPointerException if the outcome was never set
     */
    public OperationLogDraft build() {
      Objects.requireNonNull(outcome, "outcome must be set (succeeded/rejected/failed/outcome)");
      Completion resolved = completion == null ? defaultCompletion(outcome) : completion;
      return new OperationLogDraft(
          invocation,
          operationCode,
          target,
          OperationResult.of(outcome, resolved),
          summary,
          changes,
          details,
          failure,
          idempotencyKey,
          templateRef);
    }

    private static Completion defaultCompletion(Outcome outcome) {
      return outcome == Outcome.FAILED ? Completion.ROLLED_BACK : Completion.COMMITTED;
    }
  }
}
