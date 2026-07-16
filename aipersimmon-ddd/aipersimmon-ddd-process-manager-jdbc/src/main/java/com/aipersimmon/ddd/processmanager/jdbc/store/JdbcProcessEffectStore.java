package com.aipersimmon.ddd.processmanager.jdbc.store;

import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inserts staged effects into {@code aipersimmon_process_effect}. Effects are written
 * {@code PENDING} and immediately due, in the same transaction as the transition that
 * produced them; a relay claims and delivers them afterwards (a later slice). The
 * {@code UNIQUE(transition_id, effect_index)} constraint makes a transaction retry
 * unable to double-insert an effect.
 */
public final class JdbcProcessEffectStore {

    private final JdbcTemplate jdbc;

    public JdbcProcessEffectStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ProcessEffectInsert e, Instant now) {
        Timestamp ts = Timestamp.from(now);
        jdbc.update("""
                INSERT INTO aipersimmon_process_effect (
                    effect_id, instance_id, transition_id, effect_index, effect_kind,
                    payload_type, payload_version, payload, message_id, correlation_id, causation_id, trace_id,
                    status, attempts, next_attempt_at, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                e.effectId(),
                e.instanceId().value(),
                e.transitionId(),
                e.effectIndex(),
                e.kind().name(),
                e.payloadType(),
                e.payloadVersion(),
                Payloads.toText(e.payload()),
                e.messageId(),
                e.correlationId(),
                e.causationId(),
                e.traceId(),
                EffectStatus.PENDING.name(),
                0,
                ts,
                ts, ts);
    }

    /** The lifecycle of a staged effect (design-00004 §4.6). */
    public enum EffectStatus {
        PENDING,
        IN_FLIGHT,
        DELIVERED,
        DEAD
    }
}
