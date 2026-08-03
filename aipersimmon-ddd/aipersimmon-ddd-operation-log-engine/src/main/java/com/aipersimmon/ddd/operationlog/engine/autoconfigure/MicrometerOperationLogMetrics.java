package com.aipersimmon.ddd.operationlog.engine.autoconfigure;

import com.aipersimmon.ddd.operationlog.engine.observability.AppendTags;
import com.aipersimmon.ddd.operationlog.engine.observability.OperationLogMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/**
 * A Micrometer-backed {@link OperationLogMetrics}: append counters labelled by the low-cardinality
 * {@link AppendTags} plus a {@code result} dimension, the three pipeline latency timers, and — most
 * importantly — the {@code failure.record.lost} counter. That last one is the alertable audit-gap
 * signal (a failure-path record could not be written and was swallowed so the original business
 * exception could propagate); before this bridge existed the signal was a WARN log line out of the
 * box, which no alert rule fires on. Wired only when a {@code MeterRegistry} bean is present;
 * otherwise the engine keeps its no-op.
 */
public final class MicrometerOperationLogMetrics implements OperationLogMetrics {

  private static final String PREFIX = "aipersimmon.operation.log.";
  static final String APPENDS = PREFIX + "appends";
  static final String REDACT_LATENCY = PREFIX + "redact.latency";
  static final String APPEND_LATENCY = PREFIX + "append.latency";
  static final String RENDER_LATENCY = PREFIX + "render.latency";
  static final String FAILURE_RECORD_LOST = PREFIX + "failure.record.lost";

  private final MeterRegistry registry;
  private final Timer redactLatency;

  public MicrometerOperationLogMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.redactLatency =
        Timer.builder(REDACT_LATENCY)
            .description("Time spent normalizing, redacting and freezing one draft")
            .register(registry);
  }

  @Override
  public void appendAttempted(AppendTags tags) {
    appends("attempted", tags).increment();
  }

  @Override
  public void appendSucceeded(AppendTags tags) {
    appends("succeeded", tags).increment();
  }

  @Override
  public void appendDuplicate(AppendTags tags) {
    appends("duplicate", tags).increment();
  }

  @Override
  public void appendFailed(AppendTags tags) {
    appends("failed", tags).increment();
  }

  @Override
  public void redactLatencyNanos(long nanos) {
    redactLatency.record(Duration.ofNanos(nanos));
  }

  @Override
  public void appendLatencyNanos(String sinkType, long nanos) {
    Timer.builder(APPEND_LATENCY)
        .description("Time spent inside the sink append call")
        .tag("sink", sinkType)
        .register(registry)
        .record(Duration.ofNanos(nanos));
  }

  @Override
  public void renderLatencyNanos(String operationCode, long nanos) {
    Timer.builder(RENDER_LATENCY)
        .description("Time spent rendering the templates of one operation")
        .tag("operation", operationCode)
        .register(registry)
        .record(Duration.ofNanos(nanos));
  }

  @Override
  public void failureRecordLost(String operationCode) {
    Counter.builder(FAILURE_RECORD_LOST)
        .description(
            "Failure-path audit records that could not be written and were swallowed — an audit"
                + " gap; alert on any increase")
        .tag("operation", operationCode)
        .register(registry)
        .increment();
  }

  // Micrometer memoizes by (name, tags), so building per call returns the same counter; the tag
  // set is bounded because AppendTags is bounded by contract.
  private Counter appends(String result, AppendTags tags) {
    return Counter.builder(APPENDS)
        .description("Audit append pipeline entries, by result")
        .tag("result", result)
        .tag("operation", tags.operationCode())
        .tag("outcome", tags.outcome())
        .tag("sink", tags.sinkType())
        .register(registry);
  }
}
