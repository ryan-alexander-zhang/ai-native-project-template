package com.aipersimmon.ddd.operationlog.engine.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.aipersimmon.ddd.operationlog.engine.observability.AppendTags;
import com.aipersimmon.ddd.operationlog.engine.observability.OperationLogMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The out-of-the-box Micrometer bridge: every SPI call lands on a meter, and the wiring picks the
 * bridge over the engine's no-op exactly when a {@code MeterRegistry} bean exists. The
 * failure-record-lost counter is the point of the exercise — it is the alertable audit-gap signal,
 * and out of the box it used to be only a WARN log line, which no alert rule fires on.
 */
class MicrometerOperationLogMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MicrometerOperationLogMetrics metrics = new MicrometerOperationLogMetrics(registry);
  private final AppendTags tags = new AppendTags("order.remark.update", "SUCCEEDED", "JdbcSink");

  @Test
  void everyAppendResultCountsUnderItsOwnTag() {
    metrics.appendAttempted(tags);
    metrics.appendSucceeded(tags);
    metrics.appendDuplicate(tags);
    metrics.appendFailed(tags);

    for (String result : new String[] {"attempted", "succeeded", "duplicate", "failed"}) {
      assertEquals(
          1.0,
          registry
              .get(MicrometerOperationLogMetrics.APPENDS)
              .tag("result", result)
              .tag("operation", "order.remark.update")
              .counter()
              .count(),
          result);
    }
  }

  @Test
  void theAuditGapSignalIsACounterNotJustALogLine() {
    metrics.failureRecordLost("order.remark.update");
    metrics.failureRecordLost("order.remark.update");

    assertEquals(
        2.0,
        registry
            .get(MicrometerOperationLogMetrics.FAILURE_RECORD_LOST)
            .tag("operation", "order.remark.update")
            .counter()
            .count());
  }

  @Test
  void latenciesLandOnTheirTimers() {
    metrics.redactLatencyNanos(1_000_000);
    metrics.appendLatencyNanos("JdbcSink", 2_000_000);
    metrics.renderLatencyNanos("order.remark.update", 3_000_000);

    assertEquals(1, registry.get(MicrometerOperationLogMetrics.REDACT_LATENCY).timer().count());
    assertEquals(
        1,
        registry
            .get(MicrometerOperationLogMetrics.APPEND_LATENCY)
            .tag("sink", "JdbcSink")
            .timer()
            .count());
    assertEquals(
        1,
        registry
            .get(MicrometerOperationLogMetrics.RENDER_LATENCY)
            .tag("operation", "order.remark.update")
            .timer()
            .count());
  }

  @Test
  void theBridgeWinsOverTheNoOpExactlyWhenARegistryExists() {
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    OperationLogObservabilityConfiguration.class,
                    AipersimmonDddOperationLogAutoConfiguration.class));

    runner
        .withBean(SimpleMeterRegistry.class)
        .run(
            context ->
                assertInstanceOf(
                    MicrometerOperationLogMetrics.class,
                    context.getBean(OperationLogMetrics.class)));
    runner.run(
        context ->
            assertSame(OperationLogMetrics.noOp(), context.getBean(OperationLogMetrics.class)));
  }
}
