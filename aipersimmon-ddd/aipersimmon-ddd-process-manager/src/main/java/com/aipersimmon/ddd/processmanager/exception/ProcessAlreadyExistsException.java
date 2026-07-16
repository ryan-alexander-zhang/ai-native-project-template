package com.aipersimmon.ddd.processmanager.exception;

import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessType;

/**
 * Thrown by {@code start} under the {@code reject} duplicate-business-key policy when
 * a live instance already exists for {@code (processType, businessKey)} but the start
 * arrives with a different input message id (design-00004 §3.6). Under the {@code fold}
 * policy the start is folded into a duplicate result instead of throwing.
 */
public final class ProcessAlreadyExistsException extends ProcessException {

    private final transient ProcessType processType;
    private final transient ProcessBusinessKey businessKey;

    public ProcessAlreadyExistsException(ProcessType processType, ProcessBusinessKey businessKey) {
        super("a process instance already exists for " + processType.value() + " / " + businessKey.value());
        this.processType = processType;
        this.businessKey = businessKey;
    }

    public ProcessType processType() {
        return processType;
    }

    public ProcessBusinessKey businessKey() {
        return businessKey;
    }
}
