package com.aipersimmon.ddd.processmanager.jdbc.store;

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Reads and writes the current instance snapshot in {@code aipersimmon_process_instance}. Writes
 * run in the caller's REQUIRED transaction; {@link #updateSnapshot} carries the expected revision
 * in its {@code WHERE} clause so a concurrent transition cannot overwrite it (optimistic
 * concurrency).
 */
public final class JdbcProcessInstanceStore {

  private final JdbcTemplate jdbc;

  public JdbcProcessInstanceStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<ProcessInstanceRow> find(ProcessInstanceId instanceId) {
    return jdbc
        .query(
            "SELECT * FROM aipersimmon_process_instance WHERE instance_id = ?",
            ROW_MAPPER,
            instanceId.value())
        .stream()
        .findFirst();
  }

  public Optional<ProcessInstanceRow> findForUpdate(ProcessInstanceId instanceId) {
    return jdbc
        .query(
            "SELECT * FROM aipersimmon_process_instance WHERE instance_id = ? FOR UPDATE",
            ROW_MAPPER,
            instanceId.value())
        .stream()
        .findFirst();
  }

  public Optional<ProcessInstanceRow> findByBusinessKey(
      ProcessType processType, ProcessBusinessKey businessKey) {
    return jdbc
        .query(
            "SELECT * FROM aipersimmon_process_instance WHERE process_type = ? AND business_key = ? FOR UPDATE",
            ROW_MAPPER,
            processType.value(),
            businessKey.value())
        .stream()
        .findFirst();
  }

  /** Read-only lookup of an instance by its business key (no lock), for resolving its reference. */
  public Optional<ProcessInstanceRow> readByBusinessKey(
      ProcessType processType, ProcessBusinessKey businessKey) {
    return jdbc
        .query(
            "SELECT * FROM aipersimmon_process_instance WHERE process_type = ? AND business_key = ?",
            ROW_MAPPER,
            processType.value(),
            businessKey.value())
        .stream()
        .findFirst();
  }

  public void insert(ProcessInstanceRow row, Instant now) {
    Timestamp ts = Timestamp.from(now);
    jdbc.update(
        """
                INSERT INTO aipersimmon_process_instance (
                    instance_id, process_type, business_key, definition_version, state_schema_version,
                    lifecycle, resume_lifecycle, suspension_reason, business_step, outcome, revision,
                    state_payload_type, state_payload, created_at, updated_at, ended_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        row.ref().instanceId().value(),
        row.ref().processType().value(),
        row.ref().businessKey().value(),
        row.definitionVersion().value(),
        row.stateSchemaVersion().value(),
        row.lifecycle().name(),
        row.resumeLifecycle().map(ProcessLifecycle::name).orElse(null),
        row.suspensionReason().orElse(null),
        row.step().value(),
        row.outcome().map(ProcessOutcome::value).orElse(null),
        row.revision().value(),
        row.statePayloadType(),
        Payloads.toText(row.statePayload()),
        ts,
        ts,
        row.lifecycle().isTerminal() ? ts : null);
  }

  /**
   * Update the snapshot only if the instance is still at {@code expectedRevision}.
   *
   * @return the number of rows updated: 1 on success, 0 if the revision moved on
   */
  public int updateSnapshot(ProcessInstanceRow row, ProcessRevision expectedRevision, Instant now) {
    Timestamp ts = Timestamp.from(now);
    return jdbc.update(
        """
                UPDATE aipersimmon_process_instance SET
                    lifecycle = ?, resume_lifecycle = ?, suspension_reason = ?, business_step = ?,
                    outcome = ?, revision = ?, state_payload_type = ?, state_payload = ?,
                    updated_at = ?, ended_at = ?
                WHERE instance_id = ? AND revision = ?""",
        row.lifecycle().name(),
        row.resumeLifecycle().map(ProcessLifecycle::name).orElse(null),
        row.suspensionReason().orElse(null),
        row.step().value(),
        row.outcome().map(ProcessOutcome::value).orElse(null),
        row.revision().value(),
        row.statePayloadType(),
        Payloads.toText(row.statePayload()),
        ts,
        row.lifecycle().isTerminal() ? ts : null,
        row.ref().instanceId().value(),
        expectedRevision.value());
  }

  /**
   * Move an instance to {@code SUSPENDED}, preserving the lifecycle to resume to and why it was
   * suspended, without touching the business step.
   */
  public void suspend(
      ProcessInstanceId instanceId,
      ProcessLifecycle resumeLifecycle,
      String reason,
      String source,
      String workId,
      Instant now) {
    jdbc.update(
        """
                UPDATE aipersimmon_process_instance
                SET lifecycle = ?, resume_lifecycle = ?, suspension_reason = ?,
                    suspension_source = ?, suspending_work_id = ?, updated_at = ?
                WHERE instance_id = ?""",
        ProcessLifecycle.SUSPENDED.name(),
        resumeLifecycle.name(),
        reason,
        source,
        workId,
        Timestamp.from(now),
        instanceId.value());
  }

  /**
   * Resume a suspended instance back to {@code toLifecycle}, clearing the suspension metadata,
   * without touching the business step.
   */
  public void resume(ProcessInstanceId instanceId, ProcessLifecycle toLifecycle, Instant now) {
    jdbc.update(
        """
                UPDATE aipersimmon_process_instance
                SET lifecycle = ?, resume_lifecycle = NULL, suspension_reason = NULL,
                    suspension_source = NULL, suspending_work_id = NULL, updated_at = ?
                WHERE instance_id = ?""",
        toLifecycle.name(),
        Timestamp.from(now),
        instanceId.value());
  }

  /**
   * How many instances are {@code SUSPENDED}, grouped by suspension source (SLI, per-source tag).
   */
  public Map<String, Long> countSuspendedBySource() {
    Map<String, Long> bySource = new LinkedHashMap<>();
    jdbc.query(
        """
                SELECT COALESCE(suspension_source, 'UNKNOWN') AS src, COUNT(*) AS cnt
                FROM aipersimmon_process_instance WHERE lifecycle = ?
                GROUP BY suspension_source""",
        rs -> {
          bySource.put(rs.getString("src"), rs.getLong("cnt"));
        },
        ProcessLifecycle.SUSPENDED.name());
    return bySource;
  }

  /**
   * How many active instances look stuck: {@code RUNNING}/{@code COMPENSATING}, last touched before
   * {@code updatedBefore}, and with no pending or in-flight effect or deadline to make them advance
   * (a coordinator that lost its wakeup, complementary to the max-lifetime backstop).
   */
  public long countStuck(Instant updatedBefore) {
    return jdbc.queryForObject(
        """
                SELECT COUNT(*) FROM aipersimmon_process_instance i
                WHERE i.lifecycle IN (?, ?) AND i.updated_at < ?
                  AND NOT EXISTS (SELECT 1 FROM aipersimmon_process_effect e
                                  WHERE e.instance_id = i.instance_id AND e.status IN (?, ?))
                  AND NOT EXISTS (SELECT 1 FROM aipersimmon_process_deadline d
                                  WHERE d.instance_id = i.instance_id AND d.status IN (?, ?))""",
        Long.class,
        ProcessLifecycle.RUNNING.name(),
        ProcessLifecycle.COMPENSATING.name(),
        Timestamp.from(updatedBefore),
        "PENDING",
        "IN_FLIGHT",
        "PENDING",
        "IN_FLIGHT");
  }

  /**
   * Page instances matching any subset of type/businessKey/lifecycle/step/definitionVersion, newest
   * last, for operational search. Read-only, no lock.
   */
  public List<ProcessInstanceRow> search(ProcessInstanceCriteria criteria, int limit, int offset) {
    StringBuilder sql = new StringBuilder("SELECT * FROM aipersimmon_process_instance WHERE 1 = 1");
    List<Object> args = new java.util.ArrayList<>();
    criteria
        .processType()
        .ifPresent(
            v -> {
              sql.append(" AND process_type = ?");
              args.add(v.value());
            });
    criteria
        .businessKey()
        .ifPresent(
            v -> {
              sql.append(" AND business_key = ?");
              args.add(v.value());
            });
    criteria
        .lifecycle()
        .ifPresent(
            v -> {
              sql.append(" AND lifecycle = ?");
              args.add(v.name());
            });
    criteria
        .step()
        .ifPresent(
            v -> {
              sql.append(" AND business_step = ?");
              args.add(v.value());
            });
    criteria
        .definitionVersion()
        .ifPresent(
            v -> {
              sql.append(" AND definition_version = ?");
              args.add(v.value());
            });
    sql.append(" ORDER BY created_at, instance_id LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());
  }

  /** List active instances that look stuck; see {@link #countStuck}. */
  public List<ProcessInstanceRow> findStuck(Instant updatedBefore, int limit) {
    return jdbc.query(
        """
                SELECT * FROM aipersimmon_process_instance i
                WHERE i.lifecycle IN (?, ?) AND i.updated_at < ?
                  AND NOT EXISTS (SELECT 1 FROM aipersimmon_process_effect e
                                  WHERE e.instance_id = i.instance_id AND e.status IN (?, ?))
                  AND NOT EXISTS (SELECT 1 FROM aipersimmon_process_deadline d
                                  WHERE d.instance_id = i.instance_id AND d.status IN (?, ?))
                ORDER BY i.updated_at, i.instance_id LIMIT ?""",
        ROW_MAPPER,
        ProcessLifecycle.RUNNING.name(),
        ProcessLifecycle.COMPENSATING.name(),
        Timestamp.from(updatedBefore),
        "PENDING",
        "IN_FLIGHT",
        "PENDING",
        "IN_FLIGHT",
        limit);
  }

  /**
   * Distinct {@code (definitionVersion, stateSchemaVersion)} pairs referenced by live instances.
   * Only {@code RUNNING}/{@code COMPENSATING}/{@code SUSPENDED} instances pin a version; terminal
   * history ({@code COMPLETED}/{@code FAILED}/{@code CANCELLED}) does not, so an old definition can
   * be retired once no live instance depends on it (aligns with the startup validator's "live
   * instance" wording).
   */
  public List<VersionRef> distinctVersionsInUse() {
    return jdbc.query(
        """
                SELECT DISTINCT process_type, definition_version, state_schema_version
                FROM aipersimmon_process_instance
                WHERE lifecycle IN (?, ?, ?)""",
        (rs, n) ->
            new VersionRef(
                new ProcessType(rs.getString("process_type")),
                new DefinitionVersion(rs.getString("definition_version")),
                new StateSchemaVersion(rs.getInt("state_schema_version"))),
        ProcessLifecycle.RUNNING.name(),
        ProcessLifecycle.COMPENSATING.name(),
        ProcessLifecycle.SUSPENDED.name());
  }

  /** A distinct schema-version pair a live instance depends on, for startup fail-fast. */
  public record VersionRef(
      ProcessType processType,
      DefinitionVersion definitionVersion,
      StateSchemaVersion stateSchemaVersion) {}

  private static final RowMapper<ProcessInstanceRow> ROW_MAPPER = JdbcProcessInstanceStore::mapRow;

  private static ProcessInstanceRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    ProcessRef ref =
        new ProcessRef(
            new ProcessInstanceId(rs.getString("instance_id")),
            new ProcessType(rs.getString("process_type")),
            new ProcessBusinessKey(rs.getString("business_key")));
    String outcome = rs.getString("outcome");
    String resume = rs.getString("resume_lifecycle");
    String suspensionReason = rs.getString("suspension_reason");
    return new ProcessInstanceRow(
        ref,
        new DefinitionVersion(rs.getString("definition_version")),
        new StateSchemaVersion(rs.getInt("state_schema_version")),
        ProcessLifecycle.valueOf(rs.getString("lifecycle")),
        new ProcessStep(rs.getString("business_step")),
        Optional.ofNullable(outcome).map(ProcessOutcome::new),
        new ProcessRevision(rs.getLong("revision")),
        rs.getString("state_payload_type"),
        Payloads.fromText(rs.getString("state_payload")),
        Optional.ofNullable(resume).map(ProcessLifecycle::valueOf),
        Optional.ofNullable(suspensionReason));
  }
}
