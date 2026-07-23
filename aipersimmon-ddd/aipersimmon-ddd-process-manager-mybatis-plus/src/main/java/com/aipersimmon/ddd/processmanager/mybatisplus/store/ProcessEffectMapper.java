package com.aipersimmon.ddd.processmanager.mybatisplus.store;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * MyBatis mapper for {@code aipersimmon_process_effect}. SQL mirrors {@code JdbcProcessEffectStore}
 * statement-for-statement; the lease-token fencing on the mark/retry/dead/cancel transitions is
 * preserved verbatim.
 */
public interface ProcessEffectMapper {

  @Select("SELECT MAX(seq) FROM aipersimmon_process_effect WHERE instance_id = #{instanceId}")
  Long maxSeq(@Param("instanceId") String instanceId);

  @Update(
      "INSERT INTO aipersimmon_process_effect ( effect_id, instance_id, transition_id,"
          + " effect_index, seq, effect_kind, payload_type, payload_version, payload, message_id,"
          + " correlation_id, causation_id, traceparent, trace_state, status, attempts,"
          + " next_attempt_at, created_at, updated_at) VALUES (#{effectId}, #{instanceId},"
          + " #{transitionId}, #{effectIndex}, #{seq}, #{effectKind}, #{payloadType},"
          + " #{payloadVersion}, #{payload}, #{messageId}, #{correlationId},"
          + " #{causationId,jdbcType=VARCHAR}, #{traceparent,jdbcType=VARCHAR},"
          + " #{traceState,jdbcType=VARCHAR}, 'PENDING', 0, #{nextAttemptAt}, #{createdAt},"
          + " #{updatedAt})")
  void insert(Map<String, Object> row);

  @Select("SELECT * FROM aipersimmon_process_effect WHERE effect_id = #{effectId}")
  EffectLoadRow load(@Param("effectId") String effectId);

  @Update(
      "UPDATE aipersimmon_process_effect SET status = 'DELIVERED', delivered_at = #{now},"
          + " updated_at = #{now}, lease_owner = NULL, lease_token = NULL, lease_until = NULL WHERE"
          + " effect_id = #{effectId} AND lease_token = #{leaseToken}")
  int markDelivered(
      @Param("effectId") String effectId,
      @Param("leaseToken") String leaseToken,
      @Param("now") Timestamp now);

  @Update(
      "UPDATE aipersimmon_process_effect SET status = 'PENDING', attempts = attempts + 1,"
          + " next_attempt_at = #{nextAttemptAt}, last_error = #{error}, updated_at = #{now},"
          + " lease_owner = NULL, lease_token = NULL, lease_until = NULL WHERE effect_id ="
          + " #{effectId} AND lease_token = #{leaseToken}")
  int scheduleRetry(
      @Param("effectId") String effectId,
      @Param("leaseToken") String leaseToken,
      @Param("nextAttemptAt") Timestamp nextAttemptAt,
      @Param("error") String error,
      @Param("now") Timestamp now);

  @Update(
      "UPDATE aipersimmon_process_effect SET status = 'DEAD', attempts = attempts + 1, last_error"
          + " = #{error}, updated_at = #{now}, lease_owner = NULL, lease_token = NULL, lease_until"
          + " = NULL WHERE effect_id = #{effectId} AND lease_token = #{leaseToken}")
  int markDead(
      @Param("effectId") String effectId,
      @Param("leaseToken") String leaseToken,
      @Param("error") String error,
      @Param("now") Timestamp now);

  @Update(
      "UPDATE aipersimmon_process_effect SET status = 'CANCELLED', updated_at = #{now},"
          + " lease_owner = NULL, lease_token = NULL, lease_until = NULL WHERE effect_id ="
          + " #{effectId} AND lease_token = #{leaseToken}")
  int markCancelled(
      @Param("effectId") String effectId,
      @Param("leaseToken") String leaseToken,
      @Param("now") Timestamp now);

  @Update(
      "UPDATE aipersimmon_process_effect SET status = 'PENDING', next_attempt_at = #{now},"
          + " last_error = NULL, updated_at = #{now}, lease_owner = NULL, lease_token = NULL,"
          + " lease_until = NULL WHERE effect_id = #{effectId} AND status = 'DEAD'")
  int redrive(@Param("effectId") String effectId, @Param("now") Timestamp now);

  @Update(
      "UPDATE aipersimmon_process_effect SET status = 'CANCELLED', updated_at = #{now} WHERE"
          + " instance_id = #{instanceId} AND status = 'PENDING'")
  int cancelPending(@Param("instanceId") String instanceId, @Param("now") Timestamp now);

  @Select("SELECT COUNT(*) FROM aipersimmon_process_effect WHERE status = 'DEAD'")
  long countDeadAll();

  @Select(
      "SELECT COUNT(*) FROM aipersimmon_process_effect WHERE instance_id = #{instanceId} AND"
          + " status = 'DEAD'")
  long countDeadForInstance(@Param("instanceId") String instanceId);

  @Select(
      "SELECT MIN(next_attempt_at) FROM aipersimmon_process_effect WHERE status = 'PENDING' AND"
          + " next_attempt_at <= #{now}")
  Timestamp oldestDuePending(@Param("now") Timestamp now);

  @Select(
      "SELECT effect_id, instance_id, effect_kind, status, attempts, message_id, next_attempt_at,"
          + " last_error, created_at FROM aipersimmon_process_effect WHERE status = #{status}"
          + " ORDER BY created_at, effect_id LIMIT #{limit}")
  List<EffectViewRow> byStatus(@Param("status") String status, @Param("limit") int limit);
}
