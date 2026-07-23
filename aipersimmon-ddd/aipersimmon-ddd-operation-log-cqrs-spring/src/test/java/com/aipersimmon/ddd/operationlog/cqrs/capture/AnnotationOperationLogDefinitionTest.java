package com.aipersimmon.ddd.operationlog.cqrs.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import com.aipersimmon.ddd.operationlog.cqrs.capture.CaptureTestFixtures.RemarkResult;
import com.aipersimmon.ddd.operationlog.cqrs.capture.CaptureTestFixtures.SilentOnFailure;
import com.aipersimmon.ddd.operationlog.cqrs.capture.CaptureTestFixtures.UpdateRemark;
import com.aipersimmon.ddd.operationlog.model.Actor;
import com.aipersimmon.ddd.operationlog.model.ClassifiedFailure;
import com.aipersimmon.ddd.operationlog.model.OperationLogDraft;
import com.aipersimmon.ddd.operationlog.model.OperationLogInvocation;
import com.aipersimmon.ddd.operationlog.model.Outcome;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AnnotationOperationLogDefinitionTest {

  private static final OperationLogInvocation INV =
      OperationLogInvocation.builder()
          .source("s")
          .tenant("t")
          .actor(Actor.system("sys"))
          .occurredAt(Instant.EPOCH)
          .build();

  private static AnnotationOperationLogDefinition compile(Class<?> commandType) {
    return AnnotationOperationLogDefinition.compile(commandType.getAnnotation(OperationLog.class));
  }

  @Test
  void success_renders_summary_target_and_code() {
    AnnotationOperationLogDefinition def = compile(UpdateRemark.class);
    OperationLogDraft draft =
        def.prepare(new UpdateRemark("o1", "hello"), INV)
            .complete(new RemarkResult(false))
            .orElseThrow();
    assertEquals(Outcome.SUCCEEDED, draft.result().outcome());
    assertEquals("order.remark.update", draft.operationCode());
    assertEquals("o1", draft.target().id());
    assertEquals("Order", draft.target().type());
    assertEquals("remark set to hello", draft.summary());
  }

  @Test
  void rejected_when_predicate_true_classifies_committed_rejected() {
    AnnotationOperationLogDefinition def = compile(UpdateRemark.class);
    OperationLogDraft draft =
        def.prepare(new UpdateRemark("o1", "hello"), INV)
            .complete(new RemarkResult(true))
            .orElseThrow();
    assertEquals(Outcome.REJECTED, draft.result().outcome());
  }

  @Test
  void failed_builds_draft_with_failure_and_rendered_summary() {
    AnnotationOperationLogDefinition def = compile(UpdateRemark.class);
    ClassifiedFailure failure = new ClassifiedFailure("boom", "TECH", "safe");
    OperationLogDraft draft =
        def.failed(new UpdateRemark("o1", "hello"), INV, failure).orElseThrow();
    assertEquals("remark update failed", draft.summary());
    assertEquals(failure, draft.failure());
    assertEquals("o1", draft.target().id());
  }

  @Test
  void failed_returns_empty_when_record_failure_is_false() {
    AnnotationOperationLogDefinition def = compile(SilentOnFailure.class);
    Optional<OperationLogDraft> draft =
        def.failed(new SilentOnFailure("o1"), INV, new ClassifiedFailure("c", "cat", "s"));
    assertTrue(draft.isEmpty());
  }
}
