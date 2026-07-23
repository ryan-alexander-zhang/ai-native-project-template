package com.aipersimmon.ddd.operationlog.cqrs.capture;

import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import com.aipersimmon.ddd.operationlog.cqrs.template.RestrictedTemplate;
import com.aipersimmon.ddd.operationlog.definition.OperationLogDefinition;
import com.aipersimmon.ddd.operationlog.definition.PreparedOperationLog;
import com.aipersimmon.ddd.operationlog.model.ClassifiedFailure;
import com.aipersimmon.ddd.operationlog.model.OperationLogDraft;
import com.aipersimmon.ddd.operationlog.model.OperationLogInvocation;
import com.aipersimmon.ddd.operationlog.model.Outcome;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A definition synthesized from an {@link OperationLog} annotation, so annotation and hand-written
 * definitions share one lifecycle. Templates are compiled at startup. The success path classifies
 * {@code SUCCEEDED} or, when {@code rejectedWhen} is truthy, committed {@code REJECTED}; the
 * failure path builds a draft whose result is stamped by the failed interceptor from the
 * classifier.
 */
public final class AnnotationOperationLogDefinition
    implements OperationLogDefinition<Object, Object> {

  private static final String TEMPLATE_VERSION = "1";

  private final String operationCode;
  private final String targetType;
  private final RestrictedTemplate targetId;
  private final RestrictedTemplate success;
  private final RestrictedTemplate failure; // nullable
  private final boolean recordFailure;
  private final RestrictedTemplate rejectedWhen; // nullable

  private AnnotationOperationLogDefinition(
      String operationCode,
      String targetType,
      RestrictedTemplate targetId,
      RestrictedTemplate success,
      RestrictedTemplate failure,
      boolean recordFailure,
      RestrictedTemplate rejectedWhen) {
    this.operationCode = operationCode;
    this.targetType = targetType;
    this.targetId = targetId;
    this.success = success;
    this.failure = failure;
    this.recordFailure = recordFailure;
    this.rejectedWhen = rejectedWhen;
  }

  /** Compile an annotation into a definition, validating every template at startup. */
  public static AnnotationOperationLogDefinition compile(OperationLog annotation) {
    return new AnnotationOperationLogDefinition(
        annotation.code(),
        annotation.targetType(),
        RestrictedTemplate.compile(annotation.targetId(), Set.of("input")),
        RestrictedTemplate.compile(annotation.success(), Set.of("input", "resultProjection")),
        annotation.failure().isEmpty()
            ? null
            : RestrictedTemplate.compile(annotation.failure(), Set.of("input", "failure")),
        annotation.recordFailure(),
        annotation.rejectedWhen().isEmpty()
            ? null
            : RestrictedTemplate.compile(
                annotation.rejectedWhen(), Set.of("input", "resultProjection")));
  }

  @Override
  public PreparedOperationLog<Object> prepare(Object input, OperationLogInvocation invocation) {
    return result -> Optional.of(completed(input, invocation, result));
  }

  @Override
  public Optional<OperationLogDraft> failed(
      Object input, OperationLogInvocation invocation, ClassifiedFailure classifiedFailure) {
    if (!recordFailure) {
      return Optional.empty();
    }
    Map<String, Object> roots = new HashMap<>();
    roots.put("input", input);
    roots.put("failure", classifiedFailure);
    String summary = failure == null ? "operation failed" : failure.render(roots);
    return Optional.of(
        OperationLogDraft.from(invocation)
            .operation(operationCode)
            .target(targetType, renderTargetId(input), null)
            .failed(classifiedFailure)
            .summary(summary)
            .templateRef(operationCode, TEMPLATE_VERSION)
            .build());
  }

  private OperationLogDraft completed(
      Object input, OperationLogInvocation invocation, Object result) {
    Map<String, Object> roots = new HashMap<>();
    roots.put("input", input);
    roots.put("resultProjection", result);
    boolean rejected = rejectedWhen != null && isTruthy(rejectedWhen.render(roots));
    return OperationLogDraft.from(invocation)
        .operation(operationCode)
        .target(targetType, renderTargetId(input), null)
        .outcome(rejected ? Outcome.REJECTED : Outcome.SUCCEEDED)
        .summary(success.render(roots))
        .templateRef(operationCode, TEMPLATE_VERSION)
        .build();
  }

  private String renderTargetId(Object input) {
    return targetId.render(Map.of("input", input));
  }

  private static boolean isTruthy(String rendered) {
    return rendered != null && "true".equalsIgnoreCase(rendered.trim());
  }
}
