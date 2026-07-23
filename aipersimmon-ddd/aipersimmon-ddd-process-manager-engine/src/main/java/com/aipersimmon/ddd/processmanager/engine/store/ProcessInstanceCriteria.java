package com.aipersimmon.ddd.processmanager.jdbc.store;

import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import java.util.Optional;

/**
 * Read filter for the operational instance search: any subset of process type, business key,
 * lifecycle, business step, and definition version. An empty filter matches every instance; {@link
 * #any()} is the starting point for a fluent narrowing.
 */
public record ProcessInstanceCriteria(
    Optional<ProcessType> processType,
    Optional<ProcessBusinessKey> businessKey,
    Optional<ProcessLifecycle> lifecycle,
    Optional<ProcessStep> step,
    Optional<DefinitionVersion> definitionVersion) {

  public static ProcessInstanceCriteria any() {
    return new ProcessInstanceCriteria(
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
  }

  public ProcessInstanceCriteria withProcessType(ProcessType value) {
    return new ProcessInstanceCriteria(
        Optional.of(value), businessKey, lifecycle, step, definitionVersion);
  }

  public ProcessInstanceCriteria withBusinessKey(ProcessBusinessKey value) {
    return new ProcessInstanceCriteria(
        processType, Optional.of(value), lifecycle, step, definitionVersion);
  }

  public ProcessInstanceCriteria withLifecycle(ProcessLifecycle value) {
    return new ProcessInstanceCriteria(
        processType, businessKey, Optional.of(value), step, definitionVersion);
  }

  public ProcessInstanceCriteria withStep(ProcessStep value) {
    return new ProcessInstanceCriteria(
        processType, businessKey, lifecycle, Optional.of(value), definitionVersion);
  }

  public ProcessInstanceCriteria withDefinitionVersion(DefinitionVersion value) {
    return new ProcessInstanceCriteria(
        processType, businessKey, lifecycle, step, Optional.of(value));
  }
}
