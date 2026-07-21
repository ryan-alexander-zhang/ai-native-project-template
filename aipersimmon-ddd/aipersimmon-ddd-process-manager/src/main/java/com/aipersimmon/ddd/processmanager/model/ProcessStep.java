package com.aipersimmon.ddd.processmanager.model;

/**
 * The consumer-defined business step of a process (for example {@code "AWAITING_PAYMENT"}). It is
 * the business progress carried in the process state, orthogonal to the runtime {@link
 * ProcessLifecycle}: a suspended instance keeps its step and resumes from it. Values are stable
 * strings owned by the consumer.
 *
 * @param value the step name; non-blank
 */
public record ProcessStep(String value) {

  public ProcessStep {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("process step value required");
    }
  }
}
