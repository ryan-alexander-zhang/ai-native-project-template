package com.aipersimmon.ddd.operationlog.cqrs.capture;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import com.aipersimmon.ddd.operationlog.cqrs.capture.CaptureTestFixtures.RemarkResult;
import com.aipersimmon.ddd.operationlog.cqrs.capture.CaptureTestFixtures.UpdateRemark;
import com.aipersimmon.ddd.operationlog.definition.OperationLogDefinition;
import com.aipersimmon.ddd.operationlog.definition.PreparedOperationLog;
import com.aipersimmon.ddd.operationlog.exception.OperationLogException;
import com.aipersimmon.ddd.operationlog.model.ClassifiedFailure;
import com.aipersimmon.ddd.operationlog.model.OperationLogDraft;
import com.aipersimmon.ddd.operationlog.model.OperationLogInvocation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OperationLogDefinitionRegistryTest {

  /** A hand-written definition with concrete type parameters, for the code-definition path. */
  static final class RemarkDefinition
      implements OperationLogDefinition<UpdateRemark, RemarkResult> {
    @Override
    public PreparedOperationLog<RemarkResult> prepare(
        UpdateRemark input, OperationLogInvocation invocation) {
      return result -> Optional.empty();
    }

    @Override
    public Optional<OperationLogDraft> failed(
        UpdateRemark input, OperationLogInvocation invocation, ClassifiedFailure failure) {
      return Optional.empty();
    }
  }

  private static AnnotationOperationLogDefinition annotated() {
    return AnnotationOperationLogDefinition.compile(
        UpdateRemark.class.getAnnotation(OperationLog.class));
  }

  @Test
  void resolves_code_definition_by_input_type() {
    OperationLogDefinitionRegistry registry =
        OperationLogDefinitionRegistry.build(List.of(new RemarkDefinition()), Map.of());
    assertTrue(registry.find(UpdateRemark.class).isPresent());
  }

  @Test
  void resolves_annotation_definition_by_type() {
    OperationLogDefinitionRegistry registry =
        OperationLogDefinitionRegistry.build(List.of(), Map.of(UpdateRemark.class, annotated()));
    assertTrue(registry.find(UpdateRemark.class).isPresent());
  }

  @Test
  void fails_fast_on_duplicate_code_definition() {
    List<OperationLogDefinition<?, ?>> two =
        List.of(new RemarkDefinition(), new RemarkDefinition());
    assertThrows(
        OperationLogException.class, () -> OperationLogDefinitionRegistry.build(two, Map.of()));
  }

  @Test
  void fails_fast_when_annotation_and_definition_target_same_type() {
    List<OperationLogDefinition<?, ?>> code = List.of(new RemarkDefinition());
    Map<Class<?>, OperationLogDefinition<?, ?>> annotated = Map.of(UpdateRemark.class, annotated());
    assertThrows(
        OperationLogException.class, () -> OperationLogDefinitionRegistry.build(code, annotated));
  }
}
