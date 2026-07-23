package com.aipersimmon.ddd.operationlog.mybatisplus;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MyBatis-Plus mapper for {@link OperationLogRecord}. The inherited {@code insert} is used on
 * engines whose duplicate is a non-fatal statement error (H2, MySQL — caught by the sink). {@link
 * #insertOnConflictDoNothing} is the PostgreSQL path, where a caught duplicate would still abort
 * the transaction; {@code ON CONFLICT DO NOTHING} makes the conflict non-fatal (0 rows). {@link
 * #findExistingRecordId} resolves the surviving row's id on convergence.
 */
public interface OperationLogMapper extends BaseMapper<OperationLogRecord> {

  @Insert(
      "INSERT INTO aipersimmon_operation_log ("
          + "record_id, source, tenant_id, idempotency_key, operation_code, "
          + "actor_type, actor_id, actor_display, target_type, target_id, target_display, "
          + "outcome, completion, summary, changes, details, "
          + "failure_code, failure_category, failure_summary, "
          + "message_id, correlation_id, causation_id, "
          + "template_key, template_version, schema_version, occurred_at, recorded_at) "
          + "VALUES (#{recordId}, #{source}, #{tenantId}, #{idempotencyKey}, #{operationCode}, "
          + "#{actorType}, #{actorId}, #{actorDisplay}, #{targetType}, #{targetId}, #{targetDisplay}, "
          + "#{outcome}, #{completion}, #{summary}, #{changes}, #{details}, "
          + "#{failureCode}, #{failureCategory}, #{failureSummary}, "
          + "#{messageId}, #{correlationId}, #{causationId}, "
          + "#{templateKey}, #{templateVersion}, #{schemaVersion}, #{occurredAt}, #{recordedAt}) "
          + "ON CONFLICT (tenant_id, source, idempotency_key) DO NOTHING")
  int insertOnConflictDoNothing(OperationLogRecord record);

  @Select(
      "SELECT record_id FROM aipersimmon_operation_log "
          + "WHERE tenant_id = #{tenantId} AND source = #{source} AND idempotency_key = #{key}")
  String findExistingRecordId(
      @Param("tenantId") String tenantId, @Param("source") String source, @Param("key") String key);
}
