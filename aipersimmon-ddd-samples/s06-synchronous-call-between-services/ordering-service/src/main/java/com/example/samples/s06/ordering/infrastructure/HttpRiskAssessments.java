package com.example.samples.s06.ordering.infrastructure;

import com.example.samples.s06.ordering.application.RiskAssessments;
import com.example.samples.s06.ordering.application.RiskUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The adapter: the only class here that knows the risk service speaks HTTP.
 *
 * <p><strong>How the callee's contract arrives.</strong> Two hand-written records, below, carrying the
 * fields this caller actually sends and reads. The three options and their costs:
 *
 * <ul>
 *   <li><strong>A shared {@code api} jar published by the callee.</strong> Cheapest to write, and it makes
 *       the callee's release the caller's recompile: every field added for somebody else arrives here, and
 *       the caller's model inherits the callee's shape. It also makes "which version of the contract does
 *       this caller hold" a Maven question rather than a runtime one.
 *   <li><strong>OpenAPI code generation.</strong> Removes the hand-copying and the drift, at the cost of a
 *       build-time dependency on the callee's spec being published, correct and versioned — and generated
 *       DTOs are still generated <em>into your</em> codebase, so the coupling is the same shape, only
 *       automated. Worth it when the contract is large.
 *   <li><strong>Hand-written, as here.</strong> Two records and a mapping function. It cannot drift
 *       silently only because the tests exercise the wire shape; what it buys is that the caller's view of
 *       the contract is exactly the subset it uses, so an unrelated change upstream is a no-op here.
 * </ul>
 *
 * A three-field contract makes the third option obviously right; a sixty-field one makes the second. What
 * is <em>not</em> a real option is passing the callee's DTO inward past this class.
 *
 * <p><strong>What it translates, and into what.</strong> Nothing above this line sees a status code:
 *
 * <ul>
 *   <li>200 → a decision, approved or not. A rejection is data, not an error.
 *   <li>a timeout or connection failure → {@link RiskUnavailableException}, after <em>one</em> retry. The
 *       retry is safe because the callee exposes a query: asking twice is asking. A state-changing remote
 *       call could not be retried this way without an idempotency key from the callee, and getting that
 *       wrong is how one request becomes two credit holds.
 *   <li>any 4xx/5xx → {@link RiskUnavailableException} as well, because from this caller's point of view a
 *       callee that answers with a problem document has still not answered the question. The two are
 *       deliberately not distinguished for the client: a 4xx means <em>this service</em> sent something
 *       wrong, which is a defect here, and the response to a defect is not to tell the customer their
 *       order was refused.
 * </ul>
 */
@Component
class HttpRiskAssessments implements RiskAssessments {

  private static final Logger log = LoggerFactory.getLogger(HttpRiskAssessments.class);

  private final RestClient riskClient;

  HttpRiskAssessments(RestClient riskClient) {
    this.riskClient = riskClient;
  }

  @Override
  public RiskDecision assess(String customerId, long amountCents) {
    try {
      return call(customerId, amountCents);
    } catch (RestClientException first) {
      // One retry, and only because this is a query. Note what is not here: a retry budget shared with
      // other callers, a circuit breaker, a bulkhead. Those belong to a resilience library or the mesh,
      // and the honest position for a sample is to name them rather than half-implement one.
      log.warn("risk assessment failed, retrying once: {}", first.getMessage());
      try {
        return call(customerId, amountCents);
      } catch (RestClientException second) {
        throw new RiskUnavailableException("the risk service could not be reached", second);
      }
    }
  }

  private RiskDecision call(String customerId, long amountCents) {
    RiskAssessmentResponse response =
        riskClient
            .post()
            .uri("/risk-assessments")
            .body(new RiskAssessmentRequest(customerId, amountCents))
            .retrieve()
            .body(RiskAssessmentResponse.class);
    if (response == null || response.approved() == null) {
      // A 200 whose body this caller cannot interpret is not an answer. Treating it as an approval would
      // be the worst possible default; treating it as a rejection would blame the customer for a schema
      // change.
      throw new RiskUnavailableException("the risk service returned no usable decision", null);
    }
    return new RiskDecision(response.approved(), response.reason());
  }

  /** What this caller sends. Its field names match the callee's contract; nothing else does. */
  record RiskAssessmentRequest(String customerId, long amountCents) {}

  /**
   * What this caller reads. {@code approved} is a boxed {@link Boolean} on purpose: a missing field must be
   * distinguishable from {@code false}, or a contract change would silently become a wave of rejections.
   */
  record RiskAssessmentResponse(Boolean approved, String reason) {}
}
