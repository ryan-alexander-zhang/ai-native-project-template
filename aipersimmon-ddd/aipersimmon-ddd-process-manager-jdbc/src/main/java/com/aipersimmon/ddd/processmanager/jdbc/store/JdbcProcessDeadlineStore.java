package com.aipersimmon.ddd.processmanager.jdbc.store;

import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import java.sql.Timestamp;
import java.time.Instant;
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
                    status, attempts, next_attempt_at, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                d.deadlineId(),
                d.instanceId().value(),
                d.name().value(),
                d.generation(),
                Timestamp.from(d.dueAt()),
                d.inputType(),
                d.inputVersion(),
                Payloads.toText(d.inputPayload()),
                DeadlineStatus.PENDING.name(),
                0,
                Timestamp.from(d.dueAt()),
                ts, ts);
    }

    /** Cancel the current (highest) still-pending generation of a name. */
    public void cancelCurrent(ProcessInstanceId instanceId, DeadlineName name, Instant now) {
        jdbc.update("""
                UPDATE aipersimmon_process_deadline SET status = ?, updated_at = ?
                WHERE instance_id = ? AND name = ? AND status = ?
                  AND generation = (SELECT MAX(generation) FROM aipersimmon_process_deadline
                                    WHERE instance_id = ? AND name = ?)""",
                DeadlineStatus.CANCELLED.name(),
                Timestamp.from(now),
                instanceId.value(),
                name.value(),
                DeadlineStatus.PENDING.name(),
                instanceId.value(),
                name.value());
    }

    /** The lifecycle of a scheduled deadline (design-00004 §4.7). */
    public enum DeadlineStatus {
        PENDING,
        IN_FLIGHT,
        FIRED,
        DEAD,
        CANCELLED
    }
}
