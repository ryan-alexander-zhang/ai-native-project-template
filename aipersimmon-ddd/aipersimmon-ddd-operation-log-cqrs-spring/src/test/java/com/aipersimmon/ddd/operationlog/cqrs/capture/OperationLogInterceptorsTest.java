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
import com.aipersimmon.ddd.operationlog.model.Completion;
import com.aipersimmon.ddd.operationlog.model.OperationLogDraft;
import com.aipersimmon.ddd.operationlog.model.Outcome;
import com.aipersimmon.ddd.operationlog.spi.ClassifiedOutcome;
import com.aipersimmon.ddd.operationlog.spi.FailureClassifier;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationLogInterceptorsTest {

  private static final CommandContext CTX = CommandContext.root("m1");

  private static OperationLogDefinitionRegistry registryWithUpdateRemark() {
    return OperationLogDefinitionRegistry.build(
        List.of(),
        Map.of(
            UpdateRemark.class,
            AnnotationOperationLogDefinition.compile(
                UpdateRemark.class.getAnnotation(OperationLog.class))));
  }

  private static OperationLogDefinitionRegistry emptyRegistry() {
    return OperationLogDefinitionRegistry.build(List.of(), Map.of());
  }

  private static final FailureClassifier CLASSIFIER =
      (throwable, invocation) -> ClassifiedOutcome.failed("boom", "TECH", "safe");

  // --- Completed interceptor ---

  @Test
  void completed_records_succeeded_and_returns_result() {
    CapturingOperationLogs logs = new CapturingOperationLogs();
    CompletedOperationLogInterceptor interceptor =
        new CompletedOperationLogInterceptor(
            registryWithUpdateRemark(), CaptureTestFixtures.invocationFactory(), logs);

    RemarkResult result = new RemarkResult(false);
    RemarkResult returned = interceptor.intercept(new UpdateRemark("o1", "hi"), CTX, () -> result);

    assertEquals(result, returned);
    assertEquals(1, logs.recorded.size());
    assertEquals(Outcome.SUCCEEDED, logs.recorded.get(0).result().outcome());
  }

  @Test
  void completed_passes_through_when_no_definition() {
    CapturingOperationLogs logs = new CapturingOperationLogs();
    CompletedOperationLogInterceptor interceptor =
        new CompletedOperationLogInterceptor(
            emptyRegistry(), CaptureTestFixtures.invocationFactory(), logs);

    RemarkResult result = new RemarkResult(false);
    assertEquals(result, interceptor.intercept(new UpdateRemark("o1", "hi"), CTX, () -> result));
    assertTrue(logs.recorded.isEmpty());
  }

  // --- Failed interceptor ---

  private FailedOperationLogInterceptor failed(
      OperationLogDefinitionRegistry registry, CapturingOperationLogs logs, boolean nested) {
    return new FailedOperationLogInterceptor(
        registry,
        CaptureTestFixtures.invocationFactory(),
        logs,
        CLASSIFIER,
        throwable -> Completion.ROLLED_BACK,
        () -> nested,
        Runnable::run);
  }

  @Test
  void failed_root_records_and_rethrows_original() {
    CapturingOperationLogs logs = new CapturingOperationLogs();
    FailedOperationLogInterceptor interceptor = failed(registryWithUpdateRemark(), logs, false);
    Invocation<RemarkResult> boom =
        () -> {
          throw new IllegalStateException("original");
        };

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> interceptor.intercept(new UpdateRemark("o1", "hi"), CTX, boom));
    assertEquals("original", thrown.getMessage());
    assertEquals(1, logs.recorded.size());
    OperationLogDraft draft = logs.recorded.get(0);
    assertEquals(Outcome.FAILED, draft.result().outcome());
    assertEquals(Completion.ROLLED_BACK, draft.result().completion());
  }

  @Test
  void failed_nested_defers_to_root() {
    CapturingOperationLogs logs = new CapturingOperationLogs();
    FailedOperationLogInterceptor interceptor = failed(registryWithUpdateRemark(), logs, true);
    Invocation<RemarkResult> boom =
        () -> {
          throw new IllegalStateException("original");
        };

    assertThrows(
        IllegalStateException.class,
        () -> interceptor.intercept(new UpdateRemark("o1", "hi"), CTX, boom));
    assertTrue(logs.recorded.isEmpty());
  }

  @Test
  void failed_record_error_is_swallowed_and_original_rethrown() {
    CapturingOperationLogs logs = new CapturingOperationLogs();
    logs.failOnRecord = true;
    FailedOperationLogInterceptor interceptor = failed(registryWithUpdateRemark(), logs, false);
    Invocation<RemarkResult> boom =
        () -> {
          throw new IllegalStateException("original");
        };

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> interceptor.intercept(new UpdateRemark("o1", "hi"), CTX, boom));
    assertEquals("original", thrown.getMessage());
  }
}
