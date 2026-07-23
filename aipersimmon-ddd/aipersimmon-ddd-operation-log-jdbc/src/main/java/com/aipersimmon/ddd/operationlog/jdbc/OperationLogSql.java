package com.aipersimmon.ddd.operationlog.jdbc;

import com.aipersimmon.ddd.operationlog.exception.OperationLogException;
import com.aipersimmon.ddd.operationlog.model.Actor;
import com.aipersimmon.ddd.operationlog.model.ClassifiedFailure;
import com.aipersimmon.ddd.operationlog.model.OperationLogEntry;
import com.aipersimmon.ddd.operationlog.model.Target;
import com.aipersimmon.ddd.operationlog.model.TemplateRef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared SQL for the {@code aipersimmon_operation_log} table: the column list, the base INSERT, the
 * parameter binding for one entry, and the lookup of an existing row's id on a duplicate. Kept in
 * one place so the two dialects share the column list (only the conflict clause differs).
 */
final class OperationLogSql {

  static final String INSERT =
      "INSERT INTO aipersimmon_operation_log ("
          + "record_id, source, tenant_id, idempotency_key, operation_code, "
          + "actor_type, actor_id, actor_display, "
          + "target_type, target_id, target_display, "
          + "outcome, completion, summary, changes, details, "
          + "failure_code, failure_category, failure_summary, "
          + "message_id, correlation_id, causation_id, "
          + "template_key, template_version, schema_version, occurred_at, recorded_at) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private static final String SELECT_EXISTING =
      "SELECT record_id FROM aipersimmon_operation_log "
          + "WHERE tenant_id = ? AND source = ? AND idempotency_key = ?";

  private OperationLogSql() {}

  static Object[] params(OperationLogEntry e, ObjectMapper mapper) {
    Actor a = e.actor();
    Target t = e.target();
    ClassifiedFailure f = e.failure();
    TemplateRef tr = e.templateRef();
    return new Object[] {
      e.recordId(),
      e.source(),
      e.tenantId(),
      e.idempotencyKey(),
      e.operationCode(),
      a.type(),
      a.id(),
      a.displayName(),
      t.type(),
      t.id(),
      t.displayName(),
      e.outcome().name(),
      e.completion().name(),
      e.summary(),
      toJson(e.changes(), mapper),
      toJson(e.details(), mapper),
      f == null ? null : f.code(),
      f == null ? null : f.category(),
      f == null ? null : f.safeSummary(),
      e.causality().messageId(),
      e.causality().correlationId(),
      e.causality().causationId(),
      tr == null ? null : tr.key(),
      tr == null ? null : tr.version(),
      e.schemaVersion(),
      utc(e.times().occurredAt()),
      utc(e.times().recordedAt())
    };
  }

  static String findExistingRecordId(JdbcTemplate jdbc, OperationLogEntry e) {
    List<String> ids =
        jdbc.query(
            SELECT_EXISTING,
            (rs, rowNum) -> rs.getString(1),
            e.tenantId(),
            e.source(),
            e.idempotencyKey());
    return ids.isEmpty() ? e.recordId() : ids.get(0);
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static String toJson(List<?> list, ObjectMapper mapper) {
    if (list.isEmpty()) {
      return null;
    }
    try {
      return mapper.writeValueAsString(list);
    } catch (JsonProcessingException e) {
      throw new OperationLogException("failed to serialize operation-log JSON payload", e);
    }
  }
}
