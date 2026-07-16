package com.aipersimmon.ddd.processmanager.runtime;

import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;

/**
 * The committed result of a {@code start} or {@code handle}: the instance reference,
 * its new revision, lifecycle and step, whether this call was a duplicate (a no-op
 * that returned the original transition's outcome), and the id of the transition it
 * produced.
 *
 * @param processRef   the instance
 * @param revision     the instance's revision after this transition
 * @param lifecycle    the runtime lifecycle after this transition
 * @param step         the business step after this transition
 * @param duplicate    true if the input was already applied (idempotent no-op)
 * @param transitionId the id of the transition this call produced (or reproduced)
 */
public record ProcessAdvanceResult(
        ProcessRef processRef,
        ProcessRevision revision,
        ProcessLifecycle lifecycle,
        ProcessStep step,
        boolean duplicate,
        String transitionId) {

    public ProcessAdvanceResult {
        if (processRef == null) {
            throw new IllegalArgumentException("processRef required");
        }
        if (revision == null) {
            throw new IllegalArgumentException("revision required");
        }
        if (lifecycle == null) {
            throw new IllegalArgumentException("lifecycle required");
        }
        if (step == null) {
            throw new IllegalArgumentException("step required");
        }
        if (transitionId == null || transitionId.isBlank()) {
            throw new IllegalArgumentException("transitionId required");
        }
    }
}
