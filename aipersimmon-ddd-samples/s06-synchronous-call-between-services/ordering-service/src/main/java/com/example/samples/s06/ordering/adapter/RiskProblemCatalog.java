package com.example.samples.s06.ordering.adapter;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.web.error.ProblemCatalog;
import com.aipersimmon.ddd.web.error.ProblemDescriptor;
import com.example.samples.s06.ordering.domain.OrderingErrorCode;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * One override, and it earns its keep: a dependency being down must not be reported as a 500.
 *
 * <p>Error codes resolve to a problem type through their {@code ErrorCategory}, and the category enum has
 * no "a dependency is unavailable" member — the nearest is {@code UNEXPECTED}, whose family default is a
 * 500. That is wrong for a caller in two ways: it says the fault is here when it is not, and it tells the
 * client not to retry when retrying is exactly right. A {@link ProblemCatalog} is the seam for the few
 * codes whose client contract genuinely differs, so this one code gets its own type and a 503.
 *
 * <p>The other new code, {@code ordering.risk-rejected}, gets no override on purpose: it rides the
 * {@code DOMAIN_RULE} family type like every other business refusal, and clients tell it apart by its
 * {@code code}. Overriding it would grow the outward problem-type catalogue for no gain — the library's
 * own guidance, and the reason the two tiers exist.
 */
@Component
class RiskProblemCatalog implements ProblemCatalog {

  @Override
  public Map<ErrorCode, ProblemDescriptor> overrides() {
    return Map.of(
        OrderingErrorCode.RISK_UNAVAILABLE,
        new ProblemDescriptor(
            "/problems/risk-unavailable", 503, "problem.title.risk-unavailable"));
  }
}
