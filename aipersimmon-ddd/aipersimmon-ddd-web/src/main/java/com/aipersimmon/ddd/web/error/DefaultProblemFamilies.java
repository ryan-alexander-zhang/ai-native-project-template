package com.aipersimmon.ddd.web.error;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import java.util.Map;

/**
 * The library's default {@link ErrorCategory} → {@link ProblemDescriptor} bindings: one stable
 * "problem family" per coarse category. This is what lets every coded error resolve to a real
 * problem type <em>without</em> a per-code registration — a client can always branch on a
 * meaningful {@code type} (the family) and then on the {@code code} extension for the specific
 * business reason.
 *
 * <p>It lives in the framework-free {@code -web} tier (plain {@code int} status, relative {@code
 * type} URIs) — never in {@code -core}, which must stay free of any HTTP or transport concern. A
 * consumer may override any family through a {@link ProblemCatalog} (per-{@code ErrorCode}) or
 * replace the {@link ProblemRegistry} bean wholesale; a project on its own domain typically
 * prefixes these relative URIs at the edge.
 *
 * <p>Note {@link ErrorCategory#UNEXPECTED} maps to {@code about:blank}/500 on purpose: an internal
 * fault has no business semantics beyond the status, so {@code about:blank} is the correct (and
 * non-leaking) type there.
 */
public final class DefaultProblemFamilies {

  /** One descriptor per category; covers every {@link ErrorCategory} value. */
  public static final Map<ErrorCategory, ProblemDescriptor> DEFAULTS =
      Map.of(
          ErrorCategory.DOMAIN_RULE,
          new ProblemDescriptor(
              "/problems/domain-rule-violation", 422, "problem.domain-rule-violation.title"),
          ErrorCategory.NOT_FOUND,
          new ProblemDescriptor(
              "/problems/resource-not-found", 404, "problem.resource-not-found.title"),
          ErrorCategory.CONFLICT,
          new ProblemDescriptor(
              "/problems/resource-conflict", 409, "problem.resource-conflict.title"),
          ErrorCategory.VALIDATION,
          new ProblemDescriptor(
              "/problems/validation-failed", 400, "problem.validation-failed.title"),
          ErrorCategory.UNAUTHORIZED,
          new ProblemDescriptor("/problems/unauthorized", 401, "problem.unauthorized.title"),
          ErrorCategory.FORBIDDEN,
          new ProblemDescriptor("/problems/forbidden", 403, "problem.forbidden.title"),
          ErrorCategory.UNEXPECTED,
          new ProblemDescriptor("about:blank", 500, "problem.internal-error.title"));

  private DefaultProblemFamilies() {}
}
