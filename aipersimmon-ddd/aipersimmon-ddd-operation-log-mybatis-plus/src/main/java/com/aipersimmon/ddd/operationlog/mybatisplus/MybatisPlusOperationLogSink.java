package com.aipersimmon.ddd.operationlog.mybatisplus;

import com.aipersimmon.ddd.operationlog.exception.OperationLogException;
import com.aipersimmon.ddd.operationlog.model.Actor;
import com.aipersimmon.ddd.operationlog.model.ClassifiedFailure;
import com.aipersimmon.ddd.operationlog.model.OperationLogEntry;
import com.aipersimmon.ddd.operationlog.model.Target;
import com.aipersimmon.ddd.operationlog.model.TemplateRef;
import com.aipersimmon.ddd.operationlog.port.AppendResult;
import com.aipersimmon.ddd.operationlog.port.OperationLogSink;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;

/**
 * The MyBatis-Plus {@link OperationLogSink}. Behaviorally equivalent to the JDBC backend: appends
 * one row in the caller's transaction; a duplicate idempotency key converges to {@link
 * AppendResult.Duplicate} without aborting the transaction — via {@code ON CONFLICT DO NOTHING} on
 * PostgreSQL, and a caught duplicate on H2/MySQL. A genuine failure propagates (fail-closed).
 */
public final class MybatisPlusOperationLogSink implements OperationLogSink {

  private final OperationLogMapper mapper;
  private final boolean postgres;
  private final ObjectMapper objectMapper;

  public MybatisPlusOperationLogSink(
      OperationLogMapper mapper, boolean postgres, ObjectMapper objectMapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.postgres = postgres;
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  @Override
  public AppendResult append(OperationLogEntry entry) {
    OperationLogRecord record = toRecord(entry);
    int rows =
        postgres ? mapper.insertOnConflictDoNothing(record) : insertCatchingDuplicate(record);
    if (rows == 0) {
      String existing =
          mapper.findExistingRecordId(entry.tenantId(), entry.source(), entry.idempotencyKey());
      return new AppendResult.Duplicate(existing == null ? entry.recordId() : existing);
    }
    return new AppendResult.Appended(entry.recordId());
  }

  private int insertCatchingDuplicate(OperationLogRecord record) {
    try {
      return mapper.insert(record);
    } catch (DuplicateKeyException duplicate) {
      return 0;
    }
  }

  private OperationLogRecord toRecord(OperationLogEntry e) {
    Actor a = e.actor();
    Target t = e.target();
    ClassifiedFailure f = e.failure();
    TemplateRef tr = e.templateRef();
    OperationLogRecord r = new OperationLogRecord();
    r.setRecordId(e.recordId());
    r.setSource(e.source());
    r.setTenantId(e.tenantId());
    r.setIdempotencyKey(e.idempotencyKey());
    r.setOperationCode(e.operationCode());
    r.setActorType(a.type());
    r.setActorId(a.id());
    r.setActorDisplay(a.displayName());
    r.setTargetType(t.type());
    r.setTargetId(t.id());
    r.setTargetDisplay(t.displayName());
    r.setOutcome(e.outcome().name());
    r.setCompletion(e.completion().name());
    r.setSummary(e.summary());
    r.setChanges(toJson(e.changes()));
    r.setDetails(toJson(e.details()));
    r.setFailureCode(f == null ? null : f.code());
    r.setFailureCategory(f == null ? null : f.category());
    r.setFailureSummary(f == null ? null : f.safeSummary());
    r.setMessageId(e.causality().messageId());
    r.setCorrelationId(e.causality().correlationId());
    r.setCausationId(e.causality().causationId());
    r.setTemplateKey(tr == null ? null : tr.key());
    r.setTemplateVersion(tr == null ? null : tr.version());
    r.setSchemaVersion(e.schemaVersion());
    r.setOccurredAt(utc(e.times().occurredAt()));
    r.setRecordedAt(utc(e.times().recordedAt()));
    return r;
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private String toJson(List<?> list) {
    if (list.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(list);
    } catch (JsonProcessingException e) {
      throw new OperationLogException("failed to serialize operation-log JSON payload", e);
    }
  }
}
