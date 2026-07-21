package com.aipersimmon.ddd.processmanager.model;

/**
 * The stable logical name of a process, chosen by the consumer (for example {@code
 * "ordering.fulfilment"}). It is never a Java class name: a definition can be refactored or
 * re-versioned without changing the identity persisted on running instances. Together with {@link
 * DefinitionVersion} it selects a {@link
 * com.aipersimmon.ddd.processmanager.definition.ProcessDefinition}.
 *
 * @param value the logical name; non-blank
 */
public record ProcessType(String value) {

  public ProcessType {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("process type value required");
    }
  }
}
