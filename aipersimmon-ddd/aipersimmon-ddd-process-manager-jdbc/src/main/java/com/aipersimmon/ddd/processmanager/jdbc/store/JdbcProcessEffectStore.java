package com.aipersimmon.ddd.processmanager.jdbc.store;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
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

    /** Load a claimed effect for dispatch, reconstructing its context from the persisted identity. */
    public Optional<ClaimedEffect> load(String effectId) {
        return jdbc.query("SELECT * FROM aipersimmon_process_effect WHERE effect_id = ?",
                (rs, n) -> new ClaimedEffect(
                        rs.getString("effect_id"),
                        new ProcessInstanceId(rs.getString("instance_id")),
                        ProcessEffectKind.valueOf(rs.getString("effect_kind")),
                        new PayloadType(rs.getString("payload_type"), rs.getInt("payload_version")),
                        Payloads.fromText(rs.getString("payload")),
                        new CommandContext(
                                rs.getString("message_id"),
                                rs.getString("correlation_id"),
                                rs.getString("causation_id"),
                                rs.getString("trace_id")),
                        rs.getInt("attempts")),
                effectId).stream().findFirst();
    }

    /** Mark an in-flight effect delivered; fenced by the lease token so a stale owner cannot. */
    public int markDelivered(String effectId, String leaseToken, Instant now) {
        Timestamp ts = Timestamp.from(now);
        return jdbc.update("""
                UPDATE aipersimmon_process_effect
                SET status = ?, delivered_at = ?, updated_at = ?,
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL
                WHERE effect_id = ? AND lease_token = ?""",
                EffectStatus.DELIVERED.name(), ts, ts, effectId, leaseToken);
    }

    /** Return an effect to PENDING for a later retry; fenced by the lease token. */
    public int scheduleRetry(String effectId, String leaseToken, Instant nextAttemptAt, String error, Instant now) {
        return jdbc.update("""
                UPDATE aipersimmon_process_effect
                SET status = ?, next_attempt_at = ?, last_error = ?, updated_at = ?,
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL
                WHERE effect_id = ? AND lease_token = ?""",
                EffectStatus.PENDING.name(), Timestamp.from(nextAttemptAt), error, Timestamp.from(now),
                effectId, leaseToken);
    }

    /** Move an effect to DEAD after exhausting retries; fenced by the lease token. */
    public int markDead(String effectId, String leaseToken, String error, Instant now) {
        Timestamp ts = Timestamp.from(now);
        return jdbc.update("""
                UPDATE aipersimmon_process_effect
                SET status = ?, last_error = ?, updated_at = ?,
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL
                WHERE effect_id = ? AND lease_token = ?""",
                EffectStatus.DEAD.name(), error, ts, effectId, leaseToken);
    }

    /** The lifecycle of a staged effect (design-00004 §4.6). */
    public enum EffectStatus {
        PENDING,
        IN_FLIGHT,
        DELIVERED,
        DEAD
    }
}
