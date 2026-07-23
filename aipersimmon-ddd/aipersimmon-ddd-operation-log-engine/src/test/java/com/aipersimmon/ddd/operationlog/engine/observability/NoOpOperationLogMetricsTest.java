package com.aipersimmon.ddd.operationlog.engine.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/** The no-op metrics must accept every instrument silently and be a stable singleton. */
class NoOpOperationLogMetricsTest {

  @Test
  void noOp_is_a_stable_singleton() {
    assertSame(OperationLogMetrics.noOp(), OperationLogMetrics.noOp());
  }

  @Test
  void every_instrument_is_a_silent_no_op() {
    OperationLogMetrics metrics = OperationLogMetrics.noOp();
    AppendTags tags = new AppendTags("order.update", "SUCCEEDED", "StubSink");
    assertDoesNotThrow(
        () -> {
          metrics.appendAttempted(tags);
          metrics.appendSucceeded(tags);
          metrics.appendDuplicate(tags);
          metrics.appendFailed(tags);
          metrics.redactLatencyNanos(1L);
          metrics.appendLatencyNanos("StubSink", 2L);
          metrics.renderLatencyNanos("order.update", 3L);
          metrics.failureRecordLost("order.update");
        });
  }
}
