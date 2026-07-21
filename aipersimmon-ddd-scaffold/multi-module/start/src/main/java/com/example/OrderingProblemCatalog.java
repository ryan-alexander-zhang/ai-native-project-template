package com.example;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.web.error.ProblemCatalog;
import com.aipersimmon.ddd.web.error.ProblemDescriptor;
import com.example.ordering.domain.shared.OrderingErrorCode;
import java.util.Map;

/**
 * The ordering context's problem-type <em>overrides</em>: only the codes that deserve their own
 * public problem type, distinct client handling, or their own documentation. It lives in the
 * bootstrap module because it joins a domain concern (the code) to a web concern (the type) — the
 * domain itself stays free of any web dependency.
 *
 * <p>Everything not listed here rides its {@code ErrorCategory} family ({@code DOMAIN_RULE →
 * /problems/domain-rule-violation}, {@code NOT_FOUND → /problems/resource-not-found}) and is
 * distinguished on the wire by its {@code code}. So the outward problem-type catalogue stays small
 * even as domain error codes grow — only {@code CREDIT_EXCEEDED} here warrants a dedicated type
 * (the client shows a top-up flow), so it overrides; {@code ORDER_EMPTY} / {@code TOO_MANY_LINES} /
 * {@code DUPLICATE_SKU} / the not-found codes ride their families.
 */
public class OrderingProblemCatalog implements ProblemCatalog {

  @Override
  public Map<ErrorCode, ProblemDescriptor> overrides() {
    return Map.of(
        OrderingErrorCode.CREDIT_EXCEEDED,
        new ProblemDescriptor(
            "/problems/insufficient-credit", 422, "ordering.insufficient-credit.title"));
  }
}
