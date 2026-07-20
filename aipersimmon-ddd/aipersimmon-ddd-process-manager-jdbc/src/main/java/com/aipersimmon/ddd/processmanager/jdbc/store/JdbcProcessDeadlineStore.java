package com.aipersimmon.ddd.processmanager.jdbc.store;

import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reads and writes {@code aipersimmon_process_deadline}. This slice persists scheduling
 * and cancellation in the advance transaction; claiming and firing due deadlines is a
 * later slice (the deadline worker). Rescheduling a name bumps its generation so a
 * stale generation firing late is a no-op.
 */
public final class JdbcProcessDeadlineStore {

    private final JdbcTemplate jdbc;

    public JdbcProcessDeadlineStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The next generation for a name on an instance: one past the current maximum, or 1. */
    public long nextGeneration(ProcessInstanceId instanceId, DeadlineName name) {
        Long max = jdbc.queryForObject(
                "SELECT MAX(generation) FROM aipersimmon_process_deadline WHERE instance_id = ? AND name = ?",
                Long.class, instanceId.value(), name.value());
        return max == null ? 1L : max + 1L;
    }

    public void schedule(ProcessDeadlineInsert d, Instant now) {
        Timestamp ts = Timestamp.from(now);
        jdbc.update("""
                INSERT INTO aipersimmon_process_deadline (
                    deadline_id, instance_id, name, generation, due_at, input_type, input_version, input_payload,
                    correlation_id, causation_id, trace_id,
                    status, attempts, next_attempt_at, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                d.deadlineId(),
                d.instanceId().value(),
                d.name().value(),
                d.generation(),
                Timestamp.from(d.dueAt()),
                d.inputType(),
                d.inputVersion(),
                Payloads.toText(d.inputPayload()),
                d.correlationId(),
                d.causationId(),
                d.traceId(),
                DeadlineStatus.PENDING.name(),
                0,
                Timestamp.from(d.dueAt()),
                ts, ts);
    }

    /**
     * Cancel the current (highest) generation of a name while it is still live — {@code PENDING}
     * or already claimed {@code IN_FLIGHT}. Covering {@code IN_FLIGHT} is what lets a
     * {@code CancelDeadline} win against a timer a worker has already picked up: the deadline
     * worker re-checks the status under its lock before firing, so a cancel that lands first
     * turns the fire into an auditable no-op.
     */
    public void cancelCurrent(ProcessInstanceId instanceId, DeadlineName name, Instant now) {
        jdbc.update("""
                UPDATE aipersimmon_process_deadline SET status = ?, updated_at = ?
                WHERE instance_id = ? AND name = ? AND status IN (?, ?)
                  AND generation = (SELECT MAX(generation) FROM aipersimmon_process_deadline
                                    WHERE instance_id = ? AND name = ?)""",
                DeadlineStatus.CANCELLED.name(),
                Timestamp.from(now),
                instanceId.value(),
                name.value(),
                DeadlineStatus.PENDING.name(),
                DeadlineStatus.IN_FLIGHT.name(),
                instanceId.value(),
                name.value());
    }

    /** Lock a deadline row and read its current status, for the worker's pre-fire re-check. */
    public Optional<DeadlineStatus> statusForUpdate(String deadlineId) {
        return jdbc.query(
                "SELECT status FROM aipersimmon_process_deadline WHERE deadline_id = ? FOR UPDATE",
                (rs, n) -> DeadlineStatus.valueOf(rs.getString("status")), deadlineId).stream().findFirst();
    }

    /** A claimed deadline loaded for firing: its identity, encoded input, causal context, and attempt count. */
    public record DeadlineRow(
            String deadlineId,
            ProcessInstanceId instanceId,
            DeadlineName name,
            long generation,
            PayloadType inputType,
            byte[] inputPayload,
            String correlationId,
            String causationId,
            String traceId,
            int attempts) {

        public DeadlineRow {
            inputPayload = inputPayload.clone();
        }

        @Override
        public byte[] inputPayload() {
            return inputPayload.clone();
        }
    }

    public Optional<DeadlineRow> load(String deadlineId) {
        return jdbc.query("SELECT * FROM aipersimmon_process_deadline WHERE deadline_id = ?",
                (rs, n) -> new DeadlineRow(
                        rs.getString("deadline_id"),
                        new ProcessInstanceId(rs.getString("instance_id")),
                        new DeadlineName(rs.getString("name")),
                        rs.getLong("generation"),
                        new PayloadType(rs.getString("input_type"), rs.getInt("input_version")),
                        Payloads.fromText(rs.getString("input_payload")),
                        rs.getString("correlation_id"),
                        rs.getString("causation_id"),
                        rs.getString("trace_id"),
                        rs.getInt("attempts")),
                deadlineId).stream().findFirst();
    }

    /** The current (highest) generation for a name, or 0 if none — to detect a stale fire. */
    public long currentGeneration(ProcessInstanceId instanceId, DeadlineName name) {
        Long max = jdbc.queryForObject(
                "SELECT MAX(generation) FROM aipersimmon_process_deadline WHERE instance_id = ? AND name = ?",
                Long.class, instanceId.value(), name.value());
        return max == null ? 0L : max;
    }

    /** Mark a fired deadline done; fenced by the lease token. */
    public int markFired(String deadlineId, String leaseToken, Instant now) {
        Timestamp ts = Timestamp.from(now);
        return jdbc.update("""
                UPDATE aipersimmon_process_deadline
                SET status = ?, completed_at = ?, updated_at = ?,
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL
                WHERE deadline_id = ? AND lease_token = ?""",
                DeadlineStatus.FIRED.name(), ts, ts, deadlineId, leaseToken);
    }

    /** Return a deadline to PENDING for a later retry, counting the failed attempt; fenced by the lease token. */
    public int scheduleRetry(String deadlineId, String leaseToken, Instant nextAttemptAt, String error, Instant now) {
        return jdbc.update("""
                UPDATE aipersimmon_process_deadline
                SET status = ?, attempts = attempts + 1, next_attempt_at = ?, last_error = ?, updated_at = ?,
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL
                WHERE deadline_id = ? AND lease_token = ?""",
                DeadlineStatus.PENDING.name(), Timestamp.from(nextAttemptAt), error, Timestamp.from(now),
                deadlineId, leaseToken);
    }

    /** Move a deadline to DEAD after exhausting retries, counting the final failed attempt; fenced by the lease token. */
    public int markDead(String deadlineId, String leaseToken, String error, Instant now) {
        Timestamp ts = Timestamp.from(now);
        return jdbc.update("""
                UPDATE aipersimmon_process_deadline
                SET status = ?, attempts = attempts + 1, last_error = ?, updated_at = ?,
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL
                WHERE deadline_id = ? AND lease_token = ?""",
                DeadlineStatus.DEAD.name(), error, ts, deadlineId, leaseToken);
    }

    /** How many deadlines are DEAD across all instances (SLI: the redrive backlog). */
    public long countDead() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM aipersimmon_process_deadline WHERE status = ?",
                Long.class, DeadlineStatus.DEAD.name());
    }

    /** How many deadlines on an instance are still DEAD (used to decide whether to resume it). */
    public long countDead(ProcessInstanceId instanceId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM aipersimmon_process_deadline WHERE instance_id = ? AND status = ?",
                Long.class, instanceId.value(), DeadlineStatus.DEAD.name());
    }

    /** Redrive a DEAD deadline back to PENDING (reusing its id, due now) for operator recovery. */
    public int redrive(String deadlineId, Instant now) {
        Timestamp ts = Timestamp.from(now);
        return jdbc.update("""
                UPDATE aipersimmon_process_deadline
                SET status = ?, next_attempt_at = ?, last_error = NULL, updated_at = ?,
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL
                WHERE deadline_id = ? AND status = ?""",
                DeadlineStatus.PENDING.name(), ts, ts, deadlineId, DeadlineStatus.DEAD.name());
    }

    /**
     * The earliest due-but-unfired {@code PENDING} deadline's scheduled attempt time, or empty if
     * none is due (SLI: deadline-worker dwell — {@code now - result} is how long the oldest due
     * deadline has waited to fire).
     */
    public Optional<Instant> oldestDuePending(Instant now) {
        Timestamp ts = jdbc.queryForObject(
                "SELECT MIN(next_attempt_at) FROM aipersimmon_process_deadline WHERE status = ? AND next_attempt_at <= ?",
                Timestamp.class, DeadlineStatus.PENDING.name(), Timestamp.from(now));
        return Optional.ofNullable(ts).map(Timestamp::toInstant);
    }

    /** List deadlines in a given status, soonest-due first, for an operator worklist. */
    public java.util.List<ProcessDeadlineView> byStatus(DeadlineStatus status, int limit) {
        return jdbc.query("""
                SELECT deadline_id, instance_id, name, generation, status, due_at,
                       attempts, next_attempt_at, last_error
                FROM aipersimmon_process_deadline
                WHERE status = ?
                ORDER BY due_at, deadline_id LIMIT ?""",
                (rs, n) -> new ProcessDeadlineView(
                        rs.getString("deadline_id"),
                        rs.getString("instance_id"),
                        rs.getString("name"),
                        rs.getLong("generation"),
                        rs.getString("status"),
                        rs.getTimestamp("due_at").toInstant(),
                        rs.getInt("attempts"),
                        Optional.ofNullable(rs.getTimestamp("next_attempt_at")).map(Timestamp::toInstant),
                        Optional.ofNullable(rs.getString("last_error"))),
                status.name(), limit);
    }

    /** Cancel all pending deadlines of an instance when a process is cancelled by an operator. */
    public int cancelPending(ProcessInstanceId instanceId, Instant now) {
        return jdbc.update("""
                UPDATE aipersimmon_process_deadline SET status = ?, completed_at = ?, updated_at = ?
                WHERE instance_id = ? AND status = ?""",
                DeadlineStatus.CANCELLED.name(), Timestamp.from(now), Timestamp.from(now),
                instanceId.value(), DeadlineStatus.PENDING.name());
    }

    /** Cancel a claimed deadline as an auditable no-op (stale generation, or an ended instance). */
    public int cancelClaimed(String deadlineId, String leaseToken, Instant now) {
        Timestamp ts = Timestamp.from(now);
        return jdbc.update("""
                UPDATE aipersimmon_process_deadline
                SET status = ?, completed_at = ?, updated_at = ?,
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL
                WHERE deadline_id = ? AND lease_token = ?""",
                DeadlineStatus.CANCELLED.name(), ts, ts, deadlineId, leaseToken);
    }

    /** The lifecycle of a scheduled deadline. */
    public enum DeadlineStatus {
        PENDING,
        IN_FLIGHT,
        FIRED,
        DEAD,
        CANCELLED
    }
}
