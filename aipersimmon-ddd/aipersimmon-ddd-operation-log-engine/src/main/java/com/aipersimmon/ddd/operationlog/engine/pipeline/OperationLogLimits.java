package com.aipersimmon.ddd.operationlog.engine.pipeline;

/**
 * Size budgets applied by the pipeline before an entry is frozen: the maximum rendered summary
 * length, the maximum number of changes and details, and the maximum single value length.
 * Over-limit text is truncated and over-limit collections are capped.
 */
public record OperationLogLimits(
    int summaryMaxChars, int maxChanges, int maxDetails, int maxValueChars) {

  /** The default budgets (summary 1024, changes/details 20, single value 512). */
  public static OperationLogLimits defaults() {
    return new OperationLogLimits(1024, 20, 20, 512);
  }
}
