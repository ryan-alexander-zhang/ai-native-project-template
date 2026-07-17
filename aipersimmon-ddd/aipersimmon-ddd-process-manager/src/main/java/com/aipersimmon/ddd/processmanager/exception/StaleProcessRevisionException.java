package com.aipersimmon.ddd.processmanager.exception;

import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;

/**
 * Thrown when an optimistic-concurrency check fails: the instance's revision changed
 * under a concurrent transition. The runtime retries a bounded number of times
 * (reload, re-decide) before surfacing this to the message layer.
 */
public final class StaleProcessRevisionException extends ProcessException {

    private final transient ProcessRef processRef;
    private final transient ProcessRevision expected;
    private final transient ProcessRevision actual;

    public StaleProcessRevisionException(ProcessRef processRef, ProcessRevision expected, ProcessRevision actual) {
        super("stale revision for instance " + processRef.instanceId().value()
                + ": expected " + expected.value() + " but found " + actual.value());
        this.processRef = processRef;
        this.expected = expected;
        this.actual = actual;
    }

    public ProcessRef processRef() {
        return processRef;
    }

    public ProcessRevision expected() {
        return expected;
    }

    public ProcessRevision actual() {
        return actual;
    }
}
