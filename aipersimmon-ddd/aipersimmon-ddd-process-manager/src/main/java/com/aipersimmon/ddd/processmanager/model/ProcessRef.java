package com.aipersimmon.ddd.processmanager.model;

/**
 * The full runtime handle of a process instance: its {@link ProcessInstanceId} plus the {@link
 * ProcessType} and {@link ProcessBusinessKey} it was started for. The runtime's {@code handle}
 * addresses an existing instance by this whole reference rather than guessing an instance from a
 * business key.
 *
 * @param instanceId the runtime-assigned instance id
 * @param processType the logical process type
 * @param businessKey the business correlation key
 */
public record ProcessRef(
    ProcessInstanceId instanceId, ProcessType processType, ProcessBusinessKey businessKey) {

  public ProcessRef {
    if (instanceId == null) {
      throw new IllegalArgumentException("instanceId required");
    }
    if (processType == null) {
      throw new IllegalArgumentException("processType required");
    }
    if (businessKey == null) {
      throw new IllegalArgumentException("businessKey required");
    }
  }
}
