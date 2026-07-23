package com.aipersimmon.ddd.operationlog.engine.observability;

/**
 * The inert {@link OperationLogMetrics}: every instrument is a no-op. It applies whenever a
 * consumer has not supplied a metrics bridge, so the component behaves exactly as it did before
 * metrics existed. A singleton because it holds no state.
 */
enum NoOpOperationLogMetrics implements OperationLogMetrics {
  INSTANCE;

  @Override
  public void appendAttempted(AppendTags tags) {
    // no-op
  }

  @Override
  public void appendSucceeded(AppendTags tags) {
    // no-op
  }

  @Override
  public void appendDuplicate(AppendTags tags) {
    // no-op
  }

  @Override
  public void appendFailed(AppendTags tags) {
    // no-op
  }

  @Override
  public void redactLatencyNanos(long nanos) {
    // no-op
  }

  @Override
  public void appendLatencyNanos(String sinkType, long nanos) {
    // no-op
  }

  @Override
  public void renderLatencyNanos(String operationCode, long nanos) {
    // no-op
  }

  @Override
  public void failureRecordLost(String operationCode) {
    // no-op
  }
}
