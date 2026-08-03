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
 *
 * <p>{@link ProblemDescriptor}'s third argument is a <strong>message-source key</strong>, not the
 * title text — hence {@code ordering.insufficient-credit.title} rather than "Insufficient credit".
 * The text lives in {@code messages.properties} (and {@code messages_zh_CN.properties}), which is
 * what lets one error code answer in the caller's language. Note the failure mode if that file is
 * missing: {@code ProblemTitleResolver} falls back to the key rather than throwing, so the
 * application starts, the request succeeds, and the client is handed a dotted identifier in the
 * field RFC 9457 reserves for a human-readable summary. The same key must exist for every family a
 * code can fall back to, not only for the codes overridden here.
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
