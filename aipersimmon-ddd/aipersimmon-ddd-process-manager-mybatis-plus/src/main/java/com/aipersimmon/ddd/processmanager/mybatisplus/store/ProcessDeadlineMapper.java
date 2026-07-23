package com.aipersimmon.ddd.processmanager.mybatisplus.store;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * MyBatis mapper for {@code aipersimmon_process_deadline}. SQL mirrors {@code
 * JdbcProcessDeadlineStore} statement-for-statement.
 */
public interface ProcessDeadlineMapper {

  @Select(
      "SELECT MAX(generation) FROM aipersimmon_process_deadline WHERE instance_id = #{instanceId}"
          + " AND name = #{name}")
  Long maxGeneration(@Param("instanceId") String instanceId, @Param("name") String name);

  @Update(
      "INSERT INTO aipersimmon_process_deadline ( deadline_id, instance_id, name, generation,"
          + " due_at, input_type, input_version, input_payload, correlation_id, causation_id,"
          + " traceparent, trace_state, status, attempts, next_attempt_at, created_at, updated_at)"
          + " VALUES (#{deadlineId}, #{instanceId}, #{name}, #{generation}, #{dueAt},"
          + " #{inputType}, #{inputVersion}, #{inputPayload}, #{correlationId,jdbcType=VARCHAR},"
          + " #{causationId,jdbcType=VARCHAR}, #{traceparent,jdbcType=VARCHAR},"
          + " #{traceState,jdbcType=VARCHAR}, 'PENDING', 0, #{nextAttemptAt}, #{createdAt},"
          + " #{updatedAt})")
  void schedule(Map<String, Object> row);

  @Update(
      "UPDATE aipersimmon_process_deadline SET status = 'CANCELLED', updated_at = #{now} WHERE"
          + " instance_id = #{instanceId} AND name = #{name} AND generation = #{generation} AND"
          + " status IN ('PENDING', 'IN_FLIGHT')")
  void cancelCurrent(
      @Param("instanceId") String instanceId,
      @Param("name") String name,
      @Param("generation") long generation,
      @Param("now") Timestamp now);

  @Update(
      "UPDATE aipersimmon_process_deadline SET status = 'CANCELLED', completed_at = #{now},"
          + " updated_at = #{now} WHERE instance_id = #{instanceId} AND status = 'PENDING'")
  int cancelPending(@Param("instanceId") String instanceId, @Param("now") Timestamp now);

  @Update(
      "UPDATE aipersimmon_process_deadline SET status = 'CANCELLED', completed_at = #{now},"
          + " updated_at = #{now}, lease_owner = NULL, lease_token = NULL, lease_until = NULL WHERE"
          + " deadline_id = #{deadlineId} AND lease_token = #{leaseToken}")
  int cancelClaimed(
      @Param("deadlineId") String deadlineId,
      @Param("leaseToken") String leaseToken,
      @Param("now") Timestamp now);

  @Select(
      "SELECT status FROM aipersimmon_process_deadline WHERE deadline_id = #{deadlineId} FOR"
          + " UPDATE")
  String statusForUpdate(@Param("deadlineId") String deadlineId);

  @Select("SELECT * FROM aipersimmon_process_deadline WHERE deadline_id = #{deadlineId}")
  DeadlineLoadRow load(@Param("deadlineId") String deadlineId);

  @Update(
      "UPDATE aipersimmon_process_deadline SET status = 'FIRED', completed_at = #{now}, updated_at"
          + " = #{now}, lease_owner = NULL, lease_token = NULL, lease_until = NULL WHERE"
          + " deadline_id = #{deadlineId} AND lease_token = #{leaseToken}")
  int markFired(
      @Param("deadlineId") String deadlineId,
      @Param("leaseToken") String leaseToken,
      @Param("now") Timestamp now);

  @Update(
      "UPDATE aipersimmon_process_deadline SET status = 'PENDING', attempts = attempts + 1,"
          + " next_attempt_at = #{nextAttemptAt}, last_error = #{error}, updated_at = #{now},"
          + " lease_owner = NULL, lease_token = NULL, lease_until = NULL WHERE deadline_id ="
          + " #{deadlineId} AND lease_token = #{leaseToken}")
  int scheduleRetry(
      @Param("deadlineId") String deadlineId,
      @Param("leaseToken") String leaseToken,
      @Param("nextAttemptAt") Timestamp nextAttemptAt,
      @Param("error") String error,
      @Param("now") Timestamp now);

  @Update(
      "UPDATE aipersimmon_process_deadline SET status = 'DEAD', attempts = attempts + 1,"
          + " last_error = #{error}, updated_at = #{now}, lease_owner = NULL, lease_token = NULL,"
          + " lease_until = NULL WHERE deadline_id = #{deadlineId} AND lease_token ="
          + " #{leaseToken}")
  int markDead(
      @Param("deadlineId") String deadlineId,
      @Param("leaseToken") String leaseToken,
      @Param("error") String error,
      @Param("now") Timestamp now);

  @Update(
      "UPDATE aipersimmon_process_deadline SET status = 'PENDING', next_attempt_at = #{now},"
          + " last_error = NULL, updated_at = #{now}, lease_owner = NULL, lease_token = NULL,"
          + " lease_until = NULL WHERE deadline_id = #{deadlineId} AND status = 'DEAD'")
  int redrive(@Param("deadlineId") String deadlineId, @Param("now") Timestamp now);

  @Select("SELECT COUNT(*) FROM aipersimmon_process_deadline WHERE status = 'DEAD'")
  long countDeadAll();

  @Select(
      "SELECT COUNT(*) FROM aipersimmon_process_deadline WHERE instance_id = #{instanceId} AND"
          + " status = 'DEAD'")
  long countDeadForInstance(@Param("instanceId") String instanceId);

  @Select(
      "SELECT MIN(next_attempt_at) FROM aipersimmon_process_deadline WHERE status = 'PENDING' AND"
          + " next_attempt_at <= #{now}")
  Timestamp oldestDuePending(@Param("now") Timestamp now);

  @Select(
      "SELECT deadline_id, instance_id, name, generation, status, due_at, attempts,"
          + " next_attempt_at, last_error FROM aipersimmon_process_deadline WHERE status ="
          + " #{status} ORDER BY due_at, deadline_id LIMIT #{limit}")
  List<DeadlineViewRow> byStatus(@Param("status") String status, @Param("limit") int limit);
}
