package com.aipersimmon.ddd.operationlog.cqrs.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandInterceptor.Invocation;
import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import com.aipersimmon.ddd.operationlog.cqrs.capture.CaptureTestFixtures.CapturingOperationLogs;
import com.aipersimmon.ddd.operationlog.cqrs.capture.CaptureTestFixtures.RemarkResult;
import com.aipersimmon.ddd.operationlog.cqrs.capture.CaptureTestFixtures.UpdateRemark;
import com.aipersimmon.ddd.operationlog.engine.observability.AppendTags;
import com.aipersimmon.ddd.operationlog.engine.observability.OperationLogMetrics;
import com.aipersimmon.ddd.operationlog.model.Completion;
import com.aipersimmon.ddd.operationlog.spi.ClassifiedOutcome;
import com.aipersimmon.ddd.operationlog.spi.FailureClassifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies the capture interceptors emit render latency and the failure-record-loss signal. */
class OperationLogInterceptorMetricsTest {

  private static final CommandContext CTX = CommandContext.root("m1");
  private static final String CODE = "order.remark.update";

  private static final FailureClassifier CLASSIFIER =
      (throwable, invocation) -> ClassifiedOutcome.failed("boom", "TECH", "safe");

  private static final class RecordingMetrics implements OperationLogMetrics {
    private final List<String> renderCodes = new ArrayList<>();
    private final List<String> lostCodes = new ArrayList<>();

    @Override
    public void appendAttempted(AppendTags tags) {}

    @Override
    public void appendSucceeded(AppendTags tags) {}

    @Override
    public void appendDuplicate(AppendTags tags) {}

    @Override
    public void appendFailed(AppendTags tags) {}

    @Override
    public void redactLatencyNanos(long nanos) {}

    @Override
    public void appendLatencyNanos(String sinkType, long nanos) {}

    @Override
    public void renderLatencyNanos(String operationCode, long nanos) {
      renderCodes.add(operationCode);
    }

    @Override
    public void failureRecordLost(String operationCode) {
      lostCodes.add(operationCode);
    }
  }

  private static OperationLogDefinitionRegistry registryWithUpdateRemark() {
    return OperationLogDefinitionRegistry.build(
        List.of(),
        Map.of(
            UpdateRemark.class,
            AnnotationOperationLogDefinition.compile(
                UpdateRemark.class.getAnnotation(OperationLog.class))));
  }

  @Test
  void completed_success_reports_render_latency_with_operation_code() {
    RecordingMetrics metrics = new RecordingMetrics();
    CompletedOperationLogInterceptor interceptor =
        new CompletedOperationLogInterceptor(
            registryWithUpdateRemark(),
            CaptureTestFixtures.invocationFactory(),
            new CapturingOperationLogs(),
            metrics);

    interceptor.intercept(new UpdateRemark("o1", "hi"), CTX, () -> new RemarkResult(false));

    assertEquals(List.of(CODE), metrics.renderCodes);
    assertTrue(metrics.lostCodes.isEmpty());
  }

  @Test
  void failed_lost_record_reports_failure_record_lost_with_operation_code() {
    RecordingMetrics metrics = new RecordingMetrics();
    CapturingOperationLogs logs = new CapturingOperationLogs();
    logs.failOnRecord = true;
    FailedOperationLogInterceptor interceptor =
        new FailedOperationLogInterceptor(
            registryWithUpdateRemark(),
            CaptureTestFixtures.invocationFactory(),
            logs,
            CLASSIFIER,
            throwable -> Completion.ROLLED_BACK,
            () -> false,
            Runnable::run,
            metrics);
    Invocation<RemarkResult> boom =
        () -> {
          throw new IllegalStateException("original");
        };

    assertThrows(
        IllegalStateException.class,
        () -> interceptor.intercept(new UpdateRemark("o1", "hi"), CTX, boom));

    assertEquals(List.of(CODE), metrics.lostCodes);
    assertEquals(List.of(CODE), metrics.renderCodes, "the failure draft still rendered");
  }

  @Test
  void failed_successful_record_reports_no_loss() {
    RecordingMetrics metrics = new RecordingMetrics();
    FailedOperationLogInterceptor interceptor =
        new FailedOperationLogInterceptor(
            registryWithUpdateRemark(),
            CaptureTestFixtures.invocationFactory(),
            new CapturingOperationLogs(),
            CLASSIFIER,
            throwable -> Completion.ROLLED_BACK,
            () -> false,
            Runnable::run,
            metrics);
    Invocation<RemarkResult> boom =
        () -> {
          throw new IllegalStateException("original");
        };

    assertThrows(
        IllegalStateException.class,
        () -> interceptor.intercept(new UpdateRemark("o1", "hi"), CTX, boom));

    assertTrue(metrics.lostCodes.isEmpty());
    assertEquals(List.of(CODE), metrics.renderCodes);
  }
}
