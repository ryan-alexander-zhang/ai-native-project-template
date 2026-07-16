package com.aipersimmon.ddd.processmanager.jdbc.store;

import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Appends to the append-only transition log {@code aipersimmon_process_transition} and
 * answers the process-level dedup lookup by {@code (instance_id, input_message_id)}.
 * A history row is never overwritten.
 */
public final class JdbcProcessTransitionStore {

    private final JdbcTemplate jdbc;

    public JdbcProcessTransitionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The id of the transition an input already produced, if any (idempotency check). */
    public Optional<String> findTransitionIdByInput(ProcessInstanceId instanceId, String inputMessageId) {
        return jdbc.query(
                "SELECT transition_id FROM aipersimmon_process_transition "
                        + "WHERE instance_id = ? AND input_message_id = ?",
                (rs, n) -> rs.getString("transition_id"),
                instanceId.value(), inputMessageId).stream().findFirst();
    }

    /** The id of the most recent transition on an instance (for duplicate/no-op results). */
    public Optional<String> findLatestTransitionId(ProcessInstanceId instanceId) {
        return jdbc.query(
                "SELECT transition_id FROM aipersimmon_process_transition "
                        + "WHERE instance_id = ? ORDER BY created_at DESC, transition_id DESC LIMIT 1",
                (rs, n) -> rs.getString("transition_id"),
                instanceId.value()).stream().findFirst();
    }

    public void append(ProcessTransitionInsert t, Instant now) {
        jdbc.update("""
                INSERT INTO aipersimmon_process_transition (
                    transition_id, instance_id, input_message_id, input_type, input_version, input_payload,
                    from_lifecycle, to_lifecycle, from_step, to_step, decision_code, transition_kind, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                t.transitionId(),
                t.instanceId().value(),
                t.inputMessageId(),
                t.inputType(),
                t.inputVersion(),
                Payloads.toText(t.inputPayload()),
                t.fromLifecycle().map(ProcessLifecycle::name).orElse(null),
                t.toLifecycle().name(),
                t.fromStep().map(ProcessStep::value).orElse(null),
                t.toStep().value(),
                t.decisionCode().value(),
                t.transitionKind(),
                Timestamp.from(now));
    }
}
