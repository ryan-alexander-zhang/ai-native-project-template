package com.aipersimmon.ddd.operationlog.engine.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.operationlog.engine.observability.AppendTags;
import com.aipersimmon.ddd.operationlog.engine.observability.OperationLogMetrics;
import com.aipersimmon.ddd.operationlog.model.Actor;
import com.aipersimmon.ddd.operationlog.model.Causality;
import com.aipersimmon.ddd.operationlog.model.OperationLogDraft;
import com.aipersimmon.ddd.operationlog.model.OperationLogEntry;
import com.aipersimmon.ddd.operationlog.model.OperationLogInvocation;
import com.aipersimmon.ddd.operationlog.port.AppendResult;
import com.aipersimmon.ddd.operationlog.port.OperationLogSink;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies {@link DefaultOperationLogs} emits the append-side metrics for each result branch. */
class DefaultOperationLogsMetricsTest {

  private static final Instant RECORDED = Instant.parse("2020-01-02T00:00:00Z");

  private static final class RecordingMetrics implements OperationLogMetrics {
    private final List<String> events = new ArrayList<>();
    private AppendTags lastTags;
    private boolean redactTimed;
    private boolean appendTimed;

    @Override
    public void appendAttempted(AppendTags tags) {
      lastTags = tags;
      events.add("attempted");
    }

    @Override
    public void appendSucceeded(AppendTags tags) {
      events.add("succeeded");
    }

    @Override
    public void appendDuplicate(AppendTags tags) {
      events.add("duplicate");
    }

    @Override
    public void appendFailed(AppendTags tags) {
      events.add("failed");
    }

    @Override
    public void redactLatencyNanos(long nanos) {
      redactTimed = nanos >= 0;
    }

    @Override
    public void appendLatencyNanos(String sinkType, long nanos) {
      appendTimed = nanos >= 0;
    }

    @Override
    public void renderLatencyNanos(String operationCode, long nanos) {
      // not emitted by the engine pipeline
    }

    @Override
    public void failureRecordLost(String operationCode) {
      // not emitted by the engine pipeline
    }
  }

  private static final class StubSink implements OperationLogSink {
    private AppendResult result = new AppendResult.Appended("rec-1");
    private RuntimeException failure;

    @Override
    public AppendResult append(OperationLogEntry entry) {
      if (failure != null) {
        throw failure;
      }
      return result;
    }
  }

  private static DefaultOperationLogs logs(StubSink sink, RecordingMetrics metrics) {
    return new DefaultOperationLogs(
        sink,
        Clock.fixed(RECORDED, ZoneOffset.UTC),
        () -> "rec-1",
        OperationLogLimits.defaults(),
        metrics);
  }

  private static OperationLogDraft draft() {
    OperationLogInvocation invocation =
        OperationLogInvocation.builder()
            .source("orders-service")
            .tenant("acme")
            .actor(Actor.user("u1", "Alice"))
            .causality(Causality.none())
            .occurredAt(Instant.parse("2020-01-01T00:00:00Z"))
            .build();
    return OperationLogDraft.from(invocation)
        .operation("order.cancel")
        .target("Order", "o1", null)
        .succeeded()
        .build();
  }

  @Test
  void success_emits_attempted_succeeded_and_both_latencies_with_tags() {
    RecordingMetrics metrics = new RecordingMetrics();
    logs(new StubSink(), metrics).record(draft());

    assertEquals(List.of("attempted", "succeeded"), metrics.events);
    assertEquals(new AppendTags("order.cancel", "SUCCEEDED", "StubSink"), metrics.lastTags);
    assertTrue(metrics.redactTimed, "redact latency recorded");
    assertTrue(metrics.appendTimed, "append latency recorded");
  }

  @Test
  void duplicate_emits_attempted_then_duplicate() {
    RecordingMetrics metrics = new RecordingMetrics();
    StubSink sink = new StubSink();
    sink.result = new AppendResult.Duplicate("existing-9");
    logs(sink, metrics).record(draft());

    assertEquals(List.of("attempted", "duplicate"), metrics.events);
  }

  @Test
  void sink_failure_emits_attempted_then_failed_records_append_latency_and_rethrows() {
    RecordingMetrics metrics = new RecordingMetrics();
    StubSink sink = new StubSink();
    sink.failure = new IllegalStateException("db down");

    assertThrows(IllegalStateException.class, () -> logs(sink, metrics).record(draft()));

    assertEquals(List.of("attempted", "failed"), metrics.events);
    assertTrue(metrics.appendTimed, "append latency recorded even on failure");
  }
}
