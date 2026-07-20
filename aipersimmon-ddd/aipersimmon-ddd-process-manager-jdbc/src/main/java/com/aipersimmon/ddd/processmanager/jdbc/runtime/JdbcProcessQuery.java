package com.aipersimmon.ddd.processmanager.jdbc.runtime;

import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore.DeadlineStatus;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore.EffectStatus;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.ProcessDeadlineView;
import com.aipersimmon.ddd.processmanager.jdbc.store.ProcessEffectView;
import com.aipersimmon.ddd.processmanager.jdbc.store.ProcessInstanceCriteria;
import com.aipersimmon.ddd.processmanager.jdbc.store.ProcessInstanceRow;
import com.aipersimmon.ddd.processmanager.jdbc.store.ProcessTransitionView;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.runtime.ProcessQuery;
import com.aipersimmon.ddd.processmanager.runtime.ProcessView;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The read-only {@link ProcessQuery} over the four-table store. Beyond the
 * single-instance {@link ProcessView} it offers operational reads: paged search by
 * type/businessKey/lifecycle/step/definitionVersion, the transition timeline, pending/dead effect
 * and deadline worklists, and the stuck-instance scan. It exposes runtime metadata (identity,
 * versions, lifecycle/step, outcome, revision, suspension detail) but never decoded business state,
 * and offers no mutation — operator changes go through {@code JdbcProcessOperations}.
 */
public final class JdbcProcessQuery implements ProcessQuery {

    private final JdbcProcessInstanceStore instances;
    private final JdbcProcessTransitionStore transitions;
    private final JdbcProcessEffectStore effects;
    private final JdbcProcessDeadlineStore deadlines;
    private final Clock clock;

    public JdbcProcessQuery(
            JdbcProcessInstanceStore instances,
            JdbcProcessTransitionStore transitions,
            JdbcProcessEffectStore effects,
            JdbcProcessDeadlineStore deadlines,
            Clock clock) {
        this.instances = instances;
        this.transitions = transitions;
        this.effects = effects;
        this.deadlines = deadlines;
        this.clock = clock;
    }

    /**
     * Resolve a running instance's full {@link ProcessRef} from its business key, so a consumer that
     * only holds the business key (an inbound result event's correlation) can address the instance for
     * {@code handle}. Returns empty if no instance exists for that key.
     */
    public Optional<ProcessRef> findRef(ProcessType processType, ProcessBusinessKey businessKey) {
        return instances.readByBusinessKey(processType, businessKey).map(ProcessInstanceRow::ref);
    }

    @Override
    public Optional<ProcessView> find(ProcessRef processRef) {
        return instances.find(processRef.instanceId()).map(row -> {
            // Fail fast on a ref that names a real instanceId but the wrong processType/businessKey,
            // rather than silently returning a mismatched instance (the same guard the runtime's
            // handle and the operator cancel apply at the load boundary).
            if (!row.ref().equals(processRef)) {
                throw new IllegalArgumentException(
                        "process ref mismatch for instance " + processRef.instanceId().value()
                                + ": supplied " + processRef.processType().value()
                                + "/" + processRef.businessKey().value()
                                + " but the stored instance is "
                                + row.ref().processType().value() + "/" + row.ref().businessKey().value());
            }
            return toView(row);
        });
    }

    /** Page instances matching {@code criteria}, oldest first. */
    public List<ProcessView> search(ProcessInstanceCriteria criteria, int limit, int offset) {
        return instances.search(criteria, limit, offset).stream().map(JdbcProcessQuery::toView).toList();
    }

    /** The instance's transition timeline, chronological. */
    public List<ProcessTransitionView> timeline(ProcessRef processRef) {
        return transitions.timeline(processRef.instanceId());
    }

    /** Effects in a given delivery status, oldest first — e.g. a DEAD redrive worklist. */
    public List<ProcessEffectView> effects(EffectStatus status, int limit) {
        return effects.byStatus(status, limit);
    }

    /** Deadlines in a given status, soonest-due first — e.g. a DEAD redrive worklist. */
    public List<ProcessDeadlineView> deadlines(DeadlineStatus status, int limit) {
        return deadlines.byStatus(status, limit);
    }

    /**
     * Active instances idle past {@code threshold} with no pending work — candidates for a lost
     * wakeup, complementary to the max-lifetime backstop.
     */
    public List<ProcessView> stuckInstances(Duration threshold, int limit) {
        return instances.findStuck(clock.instant().minus(threshold), limit).stream()
                .map(JdbcProcessQuery::toView).toList();
    }

    private static ProcessView toView(ProcessInstanceRow row) {
        return new ProcessView(
                row.ref(),
                row.definitionVersion(),
                row.stateSchemaVersion(),
                row.lifecycle(),
                row.step(),
                row.outcome(),
                row.revision(),
                row.resumeLifecycle(),
                row.suspensionReason());
    }
}
