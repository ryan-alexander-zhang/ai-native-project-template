package com.aipersimmon.ddd.operationlog.engine.observability;

/**
 * The metrics seam for the Operation Log component. It is a plain SPI with a no-op default ({@link
 * #noOp()}) so the engine and the capture layer can emit metrics without a compile dependency on
 * Micrometer, OpenTelemetry, or any metrics runtime — a consumer bridges the calls to its meter
 * registry by supplying an implementation as a bean. This mirrors how {@code
 * com.aipersimmon.ddd.observability.Tracer} keeps tracing off the framework-free modules.
 *
 * <p>The instruments cover the design's observability contract: append {@code attempted} / {@code
 * succeeded} / {@code duplicate} / {@code failed} counters, the render / redact / append latencies,
 * and the independent failure-record loss. Counter labels are confined to the low-cardinality
 * {@link AppendTags}; latencies carry at most the sink type or operation code. Trace correlation
 * ({@code recordId}, {@code messageId}) is a span-attribute concern, deliberately kept out of here.
 *
 * <p>Implementations must be thread-safe and must never throw — they run on the command path, and a
 * metrics failure must not disturb an operation or its logging. Durations are passed in nanoseconds
 * (from {@link System#nanoTime()} deltas); the implementation converts to its registry's unit.
 */
public interface OperationLogMetrics {

  /** An entry entered the append pipeline (one per {@code record(..)} call). */
  void appendAttempted(AppendTags tags);

  /** The append inserted a new row. */
  void appendSucceeded(AppendTags tags);

  /** The append converged on an existing row (idempotent duplicate). */
  void appendDuplicate(AppendTags tags);

  /** The append threw (the sink failed). */
  void appendFailed(AppendTags tags);

  /** Nanoseconds spent normalizing, redacting, and freezing the draft before the sink call. */
  void redactLatencyNanos(long nanos);

  /** Nanoseconds spent inside the sink append call, labelled by sink type. */
  void appendLatencyNanos(String sinkType, long nanos);

  /** Nanoseconds spent rendering the templates for one operation, labelled by operation code. */
  void renderLatencyNanos(String operationCode, long nanos);

  /**
   * A failure-path record was lost: the independent write itself failed and was swallowed so the
   * original business exception could propagate. This is an alertable signal (audit gap), labelled
   * by operation code.
   */
  void failureRecordLost(String operationCode);

  /** The shared no-op instance; the default when no consumer bridge is wired. */
  static OperationLogMetrics noOp() {
    return NoOpOperationLogMetrics.INSTANCE;
  }
}
