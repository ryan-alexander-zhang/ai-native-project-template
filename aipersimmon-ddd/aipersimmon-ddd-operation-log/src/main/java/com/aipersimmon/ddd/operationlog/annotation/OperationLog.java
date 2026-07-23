package com.aipersimmon.ddd.operationlog.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that an application {@code Command} type should be recorded as an operation log. This is
 * additive metadata only — it does not replace the {@code Command<R>} contract. The annotation
 * compiler compiles {@code code}, {@code targetType}/{@code targetId}, the {@code success}/{@code
 * failure} templates, {@code recordFailure}, and the optional {@code rejectedWhen} predicate into a
 * synthesized definition, so annotation and hand-written definitions share one lifecycle.
 *
 * <p>Templates use the restricted property-path grammar (not full SpEL) and are compiled and
 * validated at startup. Anything richer (repository reads, complex diffs, custom redaction)
 * requires a hand-written {@code OperationLogDefinition}.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {

  /** Stable business operation code (not a Java FQCN or method name). */
  String code();

  /** The primary target's type. */
  String targetType();

  /** Restricted property path for the target id, e.g. {@code ${input.resourceId}}. */
  String targetId();

  /** Restricted template rendered on a successful/committed-rejected return. */
  String success();

  /** Restricted template rendered on the failure path; empty means none. */
  String failure() default "";

  /** Whether to record a row when the command fails. */
  boolean recordFailure() default true;

  /**
   * Optional restricted boolean predicate over the result projection; when true, a normal return is
   * classified {@code REJECTED} (committed) instead of {@code SUCCEEDED}.
   */
  String rejectedWhen() default "";
}
