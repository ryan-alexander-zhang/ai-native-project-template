package com.aipersimmon.ddd.processmanager.runtime;

import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessOutcome;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import java.util.Optional;

/**
 * A read-only snapshot of a process instance for queries and operations: identity,
 * pinned versions, current lifecycle/step, terminal outcome when present, revision,
 * and — when the instance is suspended — the lifecycle it will resume to and why it
 * was suspended. It exposes the runtime metadata, not the decoded business state
 * (which is the consumer's own concern).
 *
 * @param processRef        the instance
 * @param definitionVersion the definition version the instance is pinned to
 * @param stateSchemaVersion the state schema version of its stored state
 * @param lifecycle         the current runtime lifecycle
 * @param step              the current business step
 * @param outcome           the terminal outcome, if the instance has ended
 * @param revision          the current optimistic revision
 * @param resumeLifecycle   while suspended, the lifecycle to resume to; else empty
 * @param suspensionReason  while suspended, why; else empty
 */
public record ProcessView(
        ProcessRef processRef,
        DefinitionVersion definitionVersion,
        StateSchemaVersion stateSchemaVersion,
        ProcessLifecycle lifecycle,
        ProcessStep step,
        Optional<ProcessOutcome> outcome,
        ProcessRevision revision,
        Optional<ProcessLifecycle> resumeLifecycle,
        Optional<String> suspensionReason) {

    public ProcessView {
        if (processRef == null) {
            throw new IllegalArgumentException("processRef required");
        }
        if (definitionVersion == null) {
            throw new IllegalArgumentException("definitionVersion required");
        }
        if (stateSchemaVersion == null) {
            throw new IllegalArgumentException("stateSchemaVersion required");
        }
        if (lifecycle == null) {
            throw new IllegalArgumentException("lifecycle required");
        }
        if (step == null) {
            throw new IllegalArgumentException("step required");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("outcome optional required (use Optional.empty())");
        }
        if (revision == null) {
            throw new IllegalArgumentException("revision required");
        }
        if (resumeLifecycle == null) {
            throw new IllegalArgumentException("resumeLifecycle optional required (use Optional.empty())");
        }
        if (suspensionReason == null) {
            throw new IllegalArgumentException("suspensionReason optional required (use Optional.empty())");
        }
    }
}
