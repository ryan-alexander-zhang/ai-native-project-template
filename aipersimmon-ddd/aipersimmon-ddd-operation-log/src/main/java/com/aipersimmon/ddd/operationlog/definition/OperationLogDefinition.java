package com.aipersimmon.ddd.operationlog.definition;

import com.aipersimmon.ddd.operationlog.model.ClassifiedFailure;
import com.aipersimmon.ddd.operationlog.model.OperationLogDraft;
import com.aipersimmon.ddd.operationlog.model.OperationLogInvocation;
import java.util.Optional;

/**
 * The type-safe, framework-free capture contract for one command/input type {@code I} producing a
 * result {@code R}. A consumer implements it as a plain injectable collaborator; the annotation
 * compiler synthesizes an equivalent implementation from {@code @OperationLog}. Both flow through
 * the same {@code OperationLogs} pipeline.
 *
 * <p>The success and failure paths never share mutable state: {@code prepare} returns a fresh,
 * invocation-local {@link PreparedOperationLog}; {@code failed} reads only the input, the explicit
 * invocation, and the sanitized failure.
 *
 * @param <I> the command/input type this definition captures
 * @param <R> the result type produced on the success path
 */
public interface OperationLogDefinition<I, R> {

  /**
   * Called on the success path before the handler runs, inside the business transaction. Captures
   * an allowlisted before projection exactly once and returns an invocation-local prepared object.
   */
  PreparedOperationLog<R> prepare(I input, OperationLogInvocation invocation);

  /**
   * Called on the exception / validation / commit-failure path. Reads only the input, the explicit
   * invocation, and the sanitized failure — it must not redo a before query or reuse the success
   * frame. Returns empty to record nothing.
   */
  Optional<OperationLogDraft> failed(
      I input, OperationLogInvocation invocation, ClassifiedFailure failure);
}
