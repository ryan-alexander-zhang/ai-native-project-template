package com.aipersimmon.ddd.processmanager.exception;

import com.aipersimmon.ddd.processmanager.model.ProcessRef;

/**
 * Thrown when {@code handle} (or a query/operation) addresses a process instance that does not
 * exist.
 */
public final class ProcessNotFoundException extends ProcessException {

  private final transient ProcessRef processRef;

  public ProcessNotFoundException(ProcessRef processRef) {
    super("no process instance found for " + processRef.instanceId().value());
    this.processRef = processRef;
  }

  public ProcessRef processRef() {
    return processRef;
  }
}
