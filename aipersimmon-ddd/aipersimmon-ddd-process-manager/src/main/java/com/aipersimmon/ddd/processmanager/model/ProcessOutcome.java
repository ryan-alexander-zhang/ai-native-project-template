package com.aipersimmon.ddd.processmanager.model;

/**
 * The consumer-defined terminal business result of a process (for example {@code "ORDER_CONFIRMED"}
 * or {@code "ORDER_CANCELLED"}). Present only once the lifecycle reaches a terminal state; a
 * non-terminal decision carries none. Values are stable strings owned by the consumer.
 *
 * @param value the outcome name; non-blank
 */
public record ProcessOutcome(String value) {

  public ProcessOutcome {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("process outcome value required");
    }
  }
}
