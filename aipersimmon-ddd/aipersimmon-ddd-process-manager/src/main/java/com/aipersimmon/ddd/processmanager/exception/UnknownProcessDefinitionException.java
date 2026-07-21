package com.aipersimmon.ddd.processmanager.exception;

import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessType;

/**
 * Thrown when no {@link com.aipersimmon.ddd.processmanager.definition.ProcessDefinition} is
 * registered for a process type (no active version), or for a specific type/version a running
 * instance is pinned to. The latter guards against removing a definition still in use by live
 * instances.
 */
public final class UnknownProcessDefinitionException extends ProcessException {

  private final transient ProcessType processType;
  private final transient DefinitionVersion definitionVersion;

  public UnknownProcessDefinitionException(ProcessType processType) {
    super("no active process definition registered for type " + processType.value());
    this.processType = processType;
    this.definitionVersion = null;
  }

  public UnknownProcessDefinitionException(
      ProcessType processType, DefinitionVersion definitionVersion) {
    super(
        "no process definition registered for type "
            + processType.value()
            + " version "
            + definitionVersion.value());
    this.processType = processType;
    this.definitionVersion = definitionVersion;
  }

  public ProcessType processType() {
    return processType;
  }

  /** The specific version requested, or {@code null} when the whole type is unknown. */
  public DefinitionVersion definitionVersion() {
    return definitionVersion;
  }
}
