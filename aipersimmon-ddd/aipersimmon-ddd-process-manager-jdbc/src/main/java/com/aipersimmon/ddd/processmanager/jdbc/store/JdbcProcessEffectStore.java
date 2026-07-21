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
 * Inserts staged effects into {@code aipersimmon_process_effect}. Effects are written {@code
 * PENDING} and immediately due, in the same transaction as the transition that produced them; a
 * relay claims and delivers them afterwards (a later slice). The {@code UNIQUE(transition_id,
 * effect_index)} constraint makes a transaction retry unable to double-insert an effect.
 */
public final class JdbcProcessEffectStore {

  private final JdbcTemplate jdbc;

  public JdbcProcessEffectStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * The next per-instance ordering sequence: one past the current maximum, or 0. Callers stage
   * effects inside the advance transaction, which holds the instance row lock, so the maximum is
   * stable and the assigned seq is monotonic per instance (mirrors the deadline generation).
   */
  public long nextSeq(ProcessInstanceId instanceId) {
    Long max =
        jdbc.queryForObject(
            "SELECT MAX(seq) FROM aipersimmon_process_effect WHERE instance_id = ?",
            Long.class,
            instanceId.value());
    return max == null ? 0L : max + 1L;
  }

  public void insert(ProcessEffectInsert e, Instant now) {
    Timestamp ts = Timestamp.from(now);
    jdbc.update(
        """
                INSERT INTO aipersimmon_process_effect (
                    effect_id, instance_id, transition_id, effect_index, seq, effect_kind,
                    payload_type, payload_version, payload, message_id, correlation_id, causation_id,
                    traceparent, trace_state,
                    status, attempts, next_attempt_at, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        e.effectId(),
        e.instanceId().value(),
        e.transitionId(),
        e.effectIndex(),
        e.seq(),
        e.kind().name(),
        e.payloadType(),
        e.payloadVersion(),
        Payloads.toText(e.payload()),
        e.messageId(),
        e.correlationId(),
        e.causationId(),
        e.traceparent(),
        e.traceState(),
        EffectStatus.PENDING.name(),
        0,
        ts,
        ts,
        ts);
  }

  /** Load a claimed effect for dispatch, reconstructing its context from the persisted identity. */
  public Optional<ClaimedEffect> load(String effectId) {
    return jdbc
        .query(
            "SELECT * FROM aipersimmon_process_effect WHERE effect_id = ?",
            (rs, n) ->
                new ClaimedEffect(
                    rs.getString("effect_id"),
                    new ProcessInstanceId(rs.getString("instance_id")),
                    ProcessEffectKind.valueOf(rs.getString("effect_kind")),
                    new PayloadType(rs.getString("payload_type"), rs.getInt("payload_version")),
                    Payloads.fromText(rs.getString("payload")),
                    new CommandContext(
                        rs.getString("message_id"),
                        rs.getString("correlation_id"),
                        rs.getString("causation_id")),
                    rs.getInt("attempts"),
                    rs.getString("traceparent"),
                    rs.getString("trace_state")),
            effectId)
        .stream()
        .findFirst();
  }

  /** Mark an in-flight effect delivered; fenced by the lease token so a stale owner cannot. */
  public int markDelivered(String effectId, String leaseToken, Instant now) {
    Timestamp ts = Timestamp.from(now);
    return jdbc.update(
        """
                UPDATE aipersimmon_process_effect
                SET status = ?, delivered_at = ?, updated_at = ?,
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL
                WHERE effect_id = ? AND lease_token = ?""",
        EffectStatus.DELIVERED.name(),
        ts,
        ts,
        effectId,
        leaseToken);
  }

  /**
   * Return an effect to PENDING for a later retry, counting the failed attempt; fenced by the lease
   * token.
   */
  public int scheduleRetry(
      String effectId, String leaseToken, Instant nextAttemptAt, String error, Instant now) {
    return jdbc.update(
        """
                UPDATE aipersimmon_process_effect
                SET status = ?, attempts = attempts + 1, next_attempt_at = ?, last_error = ?, updated_at = ?,
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL
                WHERE effect_id = ? AND lease_token = ?""",
        EffectStatus.PENDING.name(),
        Timestamp.from(nextAttemptAt),
        error,
        Timestamp.from(now),
        effectId,
        leaseToken);
  }

  /**
   * Move an effect to DEAD after exhausting retries, counting the final failed attempt; fenced by
   * the lease token.
   */
  public int markDead(String effectId, String leaseToken, String error, Instant now) {
    Timestamp ts = Timestamp.from(now);
    return jdbc.update(
        """
                UPDATE aipersimmon_process_effect
                SET status = ?, attempts = attempts + 1, last_error = ?, updated_at = ?,
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL
                WHERE effect_id = ? AND lease_token = ?""",
        EffectStatus.DEAD.name(),
        error,
        ts,
        effectId,
        leaseToken);
  }

  /** Redrive a DEAD effect back to PENDING (reusing its id) for operator recovery. */
  public int redrive(String effectId, Instant now) {
    Timestamp ts = Timestamp.from(now);
    return jdbc.update(
        """
                UPDATE aipersimmon_process_effect
                SET status = ?, next_attempt_at = ?, last_error = NULL, updated_at = ?,
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL
                WHERE effect_id = ? AND status = ?""",
        EffectStatus.PENDING.name(),
        ts,
        ts,
        effectId,
        EffectStatus.DEAD.name());
  }

  /** How many effects on an instance are still DEAD (used to decide whether to resume it). */
  public long countDead(com.aipersimmon.ddd.processmanager.model.ProcessInstanceId instanceId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM aipersimmon_process_effect WHERE instance_id = ? AND status = ?",
        Long.class,
        instanceId.value(),
        EffectStatus.DEAD.name());
  }

  /** How many effects are DEAD across all instances (SLI: the redrive backlog). */
  public long countDead() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM aipersimmon_process_effect WHERE status = ?",
        Long.class,
        EffectStatus.DEAD.name());
  }

  /**
   * The earliest due-but-undelivered {@code PENDING} effect's scheduled attempt time, or empty if
   * none is due (SLI: relay dwell — {@code now - result} is how long the oldest work has waited
   * past when it should have been delivered).
   */
  public Optional<Instant> oldestDuePending(Instant now) {
    Timestamp ts =
        jdbc.queryForObject(
            "SELECT MIN(next_attempt_at) FROM aipersimmon_process_effect WHERE status = ? AND next_attempt_at <= ?",
            Timestamp.class,
            EffectStatus.PENDING.name(),
            Timestamp.from(now));
    return Optional.ofNullable(ts).map(Timestamp::toInstant);
  }

  /** List effects in a given status, oldest first, for an operator worklist. */
  public java.util.List<ProcessEffectView> byStatus(EffectStatus status, int limit) {
    return jdbc.query(
        """
                SELECT effect_id, instance_id, effect_kind, status, attempts, message_id,
                       next_attempt_at, last_error, created_at
                FROM aipersimmon_process_effect
                WHERE status = ?
                ORDER BY created_at, effect_id LIMIT ?""",
        (rs, n) ->
            new ProcessEffectView(
                rs.getString("effect_id"),
                rs.getString("instance_id"),
                rs.getString("effect_kind"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getString("message_id"),
                Optional.ofNullable(rs.getTimestamp("next_attempt_at")).map(Timestamp::toInstant),
                Optional.ofNullable(rs.getString("last_error")),
                rs.getTimestamp("created_at").toInstant()),
        status.name(),
        limit);
  }

  /** Cancel not-yet-dispatched effects when a process is cancelled by an operator. */
  public int cancelPending(
      com.aipersimmon.ddd.processmanager.model.ProcessInstanceId instanceId, Instant now) {
    return jdbc.update(
        """
                UPDATE aipersimmon_process_effect SET status = ?, updated_at = ?
                WHERE instance_id = ? AND status = ?""",
        EffectStatus.CANCELLED.name(),
        Timestamp.from(now),
        instanceId.value(),
        EffectStatus.PENDING.name());
  }

  /**
   * Fence an in-flight effect to CANCELLED without dispatching it, when its owning instance was
   * cancelled after the effect was claimed. Fenced by the lease token like {@link #markDelivered},
   * so only the current owner can retire it; the external side effect is never emitted.
   */
  public int markCancelled(String effectId, String leaseToken, Instant now) {
    Timestamp ts = Timestamp.from(now);
    return jdbc.update(
        """
                UPDATE aipersimmon_process_effect
                SET status = ?, updated_at = ?,
                    lease_owner = NULL, lease_token = NULL, lease_until = NULL
                WHERE effect_id = ? AND lease_token = ?""",
        EffectStatus.CANCELLED.name(),
        ts,
        effectId,
        leaseToken);
  }

  /** The lifecycle of a staged effect. */
  public enum EffectStatus {
    PENDING,
    IN_FLIGHT,
    DELIVERED,
    DEAD,
    CANCELLED
  }
}
