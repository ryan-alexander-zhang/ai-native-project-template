package com.aipersimmon.ddd.processmanager.jdbc.runtime;

import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.runtime.ProcessQuery;
import com.aipersimmon.ddd.processmanager.runtime.ProcessView;
import java.util.Optional;

/**
 * The read-only {@link ProcessQuery} over the instance snapshot. It exposes runtime
 * metadata (identity, versions, lifecycle/step, outcome, revision, and suspension
 * detail) but never the decoded business state, and offers no mutation. Richer paging,
 * timeline, and pending/dead queries land in a later slice.
 */
public final class JdbcProcessQuery implements ProcessQuery {

    private final JdbcProcessInstanceStore instances;

    public JdbcProcessQuery(JdbcProcessInstanceStore instances) {
        this.instances = instances;
    }

    /**
     * Resolve a running instance's full {@link ProcessRef} from its business key, so a consumer that
     * only holds the business key (an inbound result event's correlation) can address the instance for
     * {@code handle}. Returns empty if no instance exists for that key.
     */
    public Optional<ProcessRef> findRef(ProcessType processType, ProcessBusinessKey businessKey) {
        return instances.readByBusinessKey(processType, businessKey).map(row -> row.ref());
    }

    @Override
    public Optional<ProcessView> find(ProcessRef processRef) {
        return instances.find(processRef.instanceId()).map(row -> new ProcessView(
                row.ref(),
                row.definitionVersion(),
                row.stateSchemaVersion(),
                row.lifecycle(),
                row.step(),
                row.outcome(),
                row.revision(),
                row.resumeLifecycle(),
                row.suspensionReason()));
    }
}
