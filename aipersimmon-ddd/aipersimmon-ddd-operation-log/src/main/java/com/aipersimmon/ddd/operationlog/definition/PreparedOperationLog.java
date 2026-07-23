package com.aipersimmon.ddd.operationlog.definition;

import com.aipersimmon.ddd.operationlog.model.OperationLogDraft;
import java.util.Optional;

/**
 * The invocation-local object returned by {@link OperationLogDefinition#prepare}. After the handler
 * returns normally, {@code complete} classifies the result into a {@code SUCCEEDED} or committed
 * {@code REJECTED} draft, or returns empty to record nothing.
 *
 * @param <R> the handler result type
 */
@FunctionalInterface
public interface PreparedOperationLog<R> {

  /** Classify the normal-return result into a draft, or empty to skip recording. */
  Optional<OperationLogDraft> complete(R result);
}
