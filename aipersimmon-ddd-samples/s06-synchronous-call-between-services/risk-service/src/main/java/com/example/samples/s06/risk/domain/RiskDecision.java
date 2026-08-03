package com.example.samples.s06.risk.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;

/**
 * The answer: assessed, and either approved or not, with the reason it was not.
 *
 * <p><strong>"Rejected" is a successful assessment.</strong> That sentence is the API design decision
 * this whole service turns on: a rejection is the correct answer to a well-formed question, so it travels
 * as a 200 with {@code approved: false} and never as a 4xx. Using a client-error status for "the answer
 * is no" conflates it with "your request was bad", and the caller then cannot tell a business refusal
 * from its own broken payload — which is precisely the distinction it needs in order to know whether to
 * show the customer a message or page someone.
 */
@ValueObject
public record RiskDecision(boolean approved, String reason) {

  // Named approve/reject rather than approved/rejected: a record's accessor owns the noun, and a static
  // factory with the same name does not compile. The verb reads better at the call site anyway.
  public static RiskDecision approve() {
    return new RiskDecision(true, null);
  }

  public static RiskDecision reject(String reason) {
    return new RiskDecision(false, reason);
  }
}
