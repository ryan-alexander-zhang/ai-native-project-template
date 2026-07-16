package com.aipersimmon.ddd.processmanager.exception;

import com.aipersimmon.ddd.processmanager.model.ProcessRef;

/**
 * Thrown by {@code handle} while an instance is {@code SUSPENDED} (delivery or a
 * deadline exhausted its retries and awaits an operator). It is a retryable signal:
 * the runtime parks the input rather than losing it, and the instance resumes on
 * redrive (design-00004 §4.6).
 */
public final class ProcessSuspendedException extends ProcessException {

    private final transient ProcessRef processRef;

    public ProcessSuspendedException(ProcessRef processRef) {
        super("process instance " + processRef.instanceId().value() + " is suspended");
        this.processRef = processRef;
    }

    public ProcessRef processRef() {
        return processRef;
    }
}
