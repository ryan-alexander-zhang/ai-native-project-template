package com.aipersimmon.ddd.processmanager.model;

/**
 * A stable, consumer-defined code naming the decision a definition took on an input (for example
 * {@code "payment-rejected-release-stock"}). It is recorded on the transition for audit and
 * observability; it is not the business step and not the outcome.
 *
 * @param value the decision code; non-blank
 */
public record DecisionCode(String value) {

  public DecisionCode {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("decision code value required");
    }
  }
}
