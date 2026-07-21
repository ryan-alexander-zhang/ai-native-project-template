package com.aipersimmon.ddd.processmanager.jdbc.store;

import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Appends to the append-only transition log {@code aipersimmon_process_transition} and answers the
 * process-level dedup lookup by {@code (instance_id, input_message_id)}. A history row is never
 * overwritten.
 */
public final class JdbcProcessTransitionStore {

  private final JdbcTemplate jdbc;

  public JdbcProcessTransitionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The id of the transition an input already produced, if any (idempotency check). */
  public Optional<String> findTransitionIdByInput(
      ProcessInstanceId instanceId, String inputMessageId) {
    return jdbc
        .query(
            "SELECT transition_id FROM aipersimmon_process_transition "
                + "WHERE instance_id = ? AND input_message_id = ?",
            (rs, n) -> rs.getString("transition_id"),
            instanceId.value(),
            inputMessageId)
        .stream()
        .findFirst();
  }

  /** The id of the most recent transition on an instance (for duplicate/no-op results). */
  public Optional<String> findLatestTransitionId(ProcessInstanceId instanceId) {
    return jdbc
        .query(
            "SELECT transition_id FROM aipersimmon_process_transition "
                + "WHERE instance_id = ? ORDER BY transition_seq DESC LIMIT 1",
            (rs, n) -> rs.getString("transition_id"),
            instanceId.value())
        .stream()
        .findFirst();
  }

  /**
   * The next per-instance ordering sequence: one past the current maximum, or 0. Callers append
   * inside the advance transaction, which holds the instance row lock, so the maximum is stable and
   * the assigned seq is monotonic per instance (mirrors the effect {@code seq} and deadline
   * generation). It gives parked-input replay and the timeline a deterministic order that neither
   * the millisecond-precision {@code created_at} nor the random {@code transition_id} can
   * guarantee.
   */
  public long nextTransitionSeq(ProcessInstanceId instanceId) {
    Long max =
        jdbc.queryForObject(
            "SELECT MAX(transition_seq) FROM aipersimmon_process_transition WHERE instance_id = ?",
            Long.class,
            instanceId.value());
    return max == null ? 0L : max + 1L;
  }

  public void append(ProcessTransitionInsert t, Instant now) {
    jdbc.update(
        """
                INSERT INTO aipersimmon_process_transition (
                    transition_id, instance_id, transition_seq, input_message_id, input_type, input_version,
                    input_payload, from_lifecycle, to_lifecycle, from_step, to_step, decision_code,
                    transition_kind, correlation_id, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        t.transitionId(),
        t.instanceId().value(),
        nextTransitionSeq(t.instanceId()),
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
        t.correlationId(),
        Timestamp.from(now));
  }

  /**
   * Append an operator (redrive/cancel) audit transition. It records the operator and reason with a
   * synthetic input identity, so it never collides with a business input.
   */
  public void appendOperator(
      String transitionId,
      ProcessInstanceId instanceId,
      ProcessLifecycle fromLifecycle,
      ProcessLifecycle toLifecycle,
      ProcessStep fromStep,
      ProcessStep toStep,
      String kind,
      String operator,
      String reason,
      Instant now) {
    jdbc.update(
        """
                INSERT INTO aipersimmon_process_transition (
                    transition_id, instance_id, transition_seq, input_message_id, input_type, input_version,
                    input_payload, from_lifecycle, to_lifecycle, from_step, to_step, decision_code,
                    transition_kind, correlation_id, operator_id, operation_reason, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        transitionId,
        instanceId.value(),
        nextTransitionSeq(instanceId),
        transitionId,
        "aipersimmon.operator",
        1,
        Payloads.toText((reason == null ? "" : reason).getBytes(StandardCharsets.UTF_8)),
        fromLifecycle.name(),
        toLifecycle.name(),
        fromStep.value(),
        toStep.value(),
        kind.toLowerCase(java.util.Locale.ROOT),
        kind,
        null,
        operator,
        reason,
        Timestamp.from(now));
  }

  /**
   * A parked input awaiting replay after the instance resumes, with the causal context to replay
   * under.
   */
  public record ParkedInput(
      String inputMessageId, PayloadType inputType, byte[] inputPayload, String correlationId) {
    public ParkedInput {
      inputPayload = inputPayload.clone();
    }

    @Override
    public byte[] inputPayload() {
      return inputPayload.clone();
    }
  }

  /** The full transition timeline of an instance in chronological order. */
  public List<ProcessTransitionView> timeline(ProcessInstanceId instanceId) {
    return jdbc.query(
        """
                SELECT transition_id, input_message_id, from_lifecycle, to_lifecycle,
                       from_step, to_step, decision_code, transition_kind, operator_id, operation_reason, created_at
                FROM aipersimmon_process_transition
                WHERE instance_id = ?
                ORDER BY transition_seq""",
        (rs, n) ->
            new ProcessTransitionView(
                rs.getString("transition_id"),
                rs.getString("input_message_id"),
                Optional.ofNullable(rs.getString("from_lifecycle")),
                rs.getString("to_lifecycle"),
                Optional.ofNullable(rs.getString("from_step")),
                rs.getString("to_step"),
                rs.getString("decision_code"),
                rs.getString("transition_kind"),
                Optional.ofNullable(rs.getString("operator_id")),
                Optional.ofNullable(rs.getString("operation_reason")),
                rs.getTimestamp("created_at").toInstant()),
        instanceId.value());
  }

  /** The inputs parked while the instance was suspended, in arrival order. */
  public List<ParkedInput> findParkedInputs(ProcessInstanceId instanceId) {
    return jdbc.query(
        """
                SELECT input_message_id, input_type, input_version, input_payload, correlation_id
                FROM aipersimmon_process_transition
                WHERE instance_id = ? AND transition_kind = 'PARKED'
                ORDER BY transition_seq""",
        (rs, n) ->
            new ParkedInput(
                rs.getString("input_message_id"),
                new PayloadType(rs.getString("input_type"), rs.getInt("input_version")),
                Payloads.fromText(rs.getString("input_payload")),
                rs.getString("correlation_id")),
        instanceId.value());
  }
}
