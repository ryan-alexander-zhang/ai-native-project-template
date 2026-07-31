package com.aipersimmon.ddd.processmanager.exception;

import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessType;

/**
 * Thrown when {@code handle} (or a query/operation) addresses a process instance that does not
 * exist — whether by full reference or by business key.
 */
public final class ProcessNotFoundException extends ProcessException {

  private final transient ProcessRef processRef;

  public ProcessNotFoundException(ProcessRef processRef) {
    super("no process instance found for " + processRef.instanceId().value());
    this.processRef = processRef;
  }

  public ProcessNotFoundException(ProcessType processType, ProcessBusinessKey businessKey) {
    super(
        "no "
            + processType.value()
            + " instance found for business key "
            + businessKey.value()
            + " under the advancing tenant");
    this.processRef = null;
  }

  /** The addressed reference, or {@code null} when the instance was addressed by business key. */
  public ProcessRef processRef() {
    return processRef;
  }
}
