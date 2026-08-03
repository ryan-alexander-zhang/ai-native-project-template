package com.example.samples.s06.ordering.application;

/**
 * The port for the synchronous call — <strong>declared in the application layer, implemented in
 * infrastructure</strong>, which is the catalogue's question and this is the answer.
 *
 * <p>Not in the domain: a repository port belongs there because persistence is how an aggregate exists at
 * all, while "what does another bounded context think" is a collaboration between use cases. Putting it in
 * the domain would let an aggregate reach it, and an aggregate that can make a network call will.
 *
 * <p>Not in infrastructure either: then the use case would depend on the adapter, and the direction of the
 * dependency is the only thing that makes the adapter replaceable.
 *
 * <p><strong>The port speaks this context's language.</strong> No HTTP, no status codes, no problem
 * documents, no DTOs of the callee's — those all stop at the adapter. What comes back is a decision or an
 * exception this context defined. That is what lets the precheck above it be tested with three lines of
 * fake and lets the transport be swapped for gRPC, a queue, or an in-process call without the use case
 * noticing.
 */
public interface RiskAssessments {

  /**
   * Asks whether this order may be placed.
   *
   * @return the decision, in this context's terms
   * @throws RiskUnavailableException when no answer could be obtained — a timeout, a connection failure,
   *     or the callee returning something this context cannot interpret. Distinct from a negative
   *     decision on purpose: "no" and "no answer" call for different behaviour from every caller above.
   */
  RiskDecision assess(String customerId, long amountCents);

  /** The answer, in this context's words rather than the callee's. */
  record RiskDecision(boolean approved, String reason) {}
}
