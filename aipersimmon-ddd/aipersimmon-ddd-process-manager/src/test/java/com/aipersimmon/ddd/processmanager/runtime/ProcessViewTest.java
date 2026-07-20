package com.aipersimmon.ddd.processmanager.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessOutcome;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link ProcessView} enforces the same terminal/suspended invariants the runtime relies on. */
class ProcessViewTest {

    private static final ProcessRef REF = new ProcessRef(
            new ProcessInstanceId("i-1"), new ProcessType("t"), new ProcessBusinessKey("bk"));
    private static final DefinitionVersion DV = new DefinitionVersion("v1");
    private static final StateSchemaVersion SV = new StateSchemaVersion(1);
    private static final ProcessStep STEP = new ProcessStep("S1");
    private static final ProcessRevision REV = ProcessRevision.initial();

    @Test
    void terminalRequiresOutcome() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessView(
                REF, DV, SV, ProcessLifecycle.COMPLETED, STEP, Optional.empty(), REV,
                Optional.empty(), Optional.empty()), "a terminal view without an outcome is illegal");
    }

    @Test
    void nonTerminalRejectsOutcome() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessView(
                REF, DV, SV, ProcessLifecycle.RUNNING, STEP, Optional.of(new ProcessOutcome("OK")), REV,
                Optional.empty(), Optional.empty()), "a running view with an outcome is illegal");
    }

    @Test
    void suspendedRequiresResumeInfo() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessView(
                REF, DV, SV, ProcessLifecycle.SUSPENDED, STEP, Optional.empty(), REV,
                Optional.empty(), Optional.empty()), "a suspended view without resume info is illegal");
        assertDoesNotThrow(() -> new ProcessView(
                REF, DV, SV, ProcessLifecycle.SUSPENDED, STEP, Optional.empty(), REV,
                Optional.of(ProcessLifecycle.RUNNING), Optional.of("effect exhausted")));
    }

    @Test
    void nonSuspendedRejectsResumeInfo() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessView(
                REF, DV, SV, ProcessLifecycle.RUNNING, STEP, Optional.empty(), REV,
                Optional.of(ProcessLifecycle.RUNNING), Optional.empty()),
                "a running view carrying a resume lifecycle is illegal");
    }
}
