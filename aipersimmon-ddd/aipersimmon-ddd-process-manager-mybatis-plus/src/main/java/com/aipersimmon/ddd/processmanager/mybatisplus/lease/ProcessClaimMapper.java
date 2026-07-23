package com.aipersimmon.ddd.processmanager.mybatisplus.lease;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Claim SQL for effects and deadlines, in a {@code SKIP LOCKED} variant (PostgreSQL/MySQL) and a
 * plain-candidate + atomic-conditional-{@code UPDATE} variant (H2). The candidate predicates and
 * per-instance head-of-line ordering are identical to {@code JdbcProcessDialect.CANDIDATE_SQL} /
 * {@code DEADLINE_CANDIDATE_SQL}; only the concurrency mechanism differs.
 */
public interface ProcessClaimMapper {

  @Select(
      "SELECT e.effect_id FROM aipersimmon_process_effect e WHERE ((e.status = 'PENDING' AND"
          + " e.next_attempt_at <= #{now}) OR (e.status = 'IN_FLIGHT' AND e.lease_until <="
          + " #{now})) AND NOT EXISTS ( SELECT 1 FROM aipersimmon_process_effect b WHERE"
          + " b.instance_id = e.instance_id AND b.status <> 'DELIVERED' AND b.seq < e.seq) ORDER BY"
          + " e.seq LIMIT #{limit} FOR UPDATE SKIP LOCKED")
  List<String> candidateEffectsSkipLocked(@Param("now") Timestamp now, @Param("limit") int limit);

  @Select(
      "SELECT e.effect_id FROM aipersimmon_process_effect e WHERE ((e.status = 'PENDING' AND"
          + " e.next_attempt_at <= #{now}) OR (e.status = 'IN_FLIGHT' AND e.lease_until <="
          + " #{now})) AND NOT EXISTS ( SELECT 1 FROM aipersimmon_process_effect b WHERE"
          + " b.instance_id = e.instance_id AND b.status <> 'DELIVERED' AND b.seq < e.seq) ORDER BY"
          + " e.seq LIMIT #{limit}")
  List<String> candidateEffects(@Param("now") Timestamp now, @Param("limit") int limit);

  @Update(
      "UPDATE aipersimmon_process_effect SET status = 'IN_FLIGHT', lease_owner = #{owner},"
          + " lease_token = #{token}, lease_until = #{until}, updated_at = #{now} WHERE effect_id ="
          + " #{id}")
  void markEffectInFlight(
      @Param("id") String id,
      @Param("owner") String owner,
      @Param("token") String token,
      @Param("until") Timestamp until,
      @Param("now") Timestamp now);

  @Update(
      "UPDATE aipersimmon_process_effect SET status = 'IN_FLIGHT', lease_owner = #{owner},"
          + " lease_token = #{token}, lease_until = #{until}, updated_at = #{now} WHERE effect_id ="
          + " #{id} AND ((status = 'PENDING' AND next_attempt_at <= #{now}) OR (status ="
          + " 'IN_FLIGHT' AND lease_until <= #{now}))")
  int markEffectInFlightIfDue(
      @Param("id") String id,
      @Param("owner") String owner,
      @Param("token") String token,
      @Param("until") Timestamp until,
      @Param("now") Timestamp now);

  @Select(
      "SELECT d.deadline_id FROM aipersimmon_process_deadline d JOIN aipersimmon_process_instance"
          + " i ON i.instance_id = d.instance_id WHERE ((d.status = 'PENDING' AND d.next_attempt_at"
          + " <= #{now}) OR (d.status = 'IN_FLIGHT' AND d.lease_until <= #{now})) AND i.lifecycle"
          + " IN ('RUNNING', 'COMPENSATING') ORDER BY d.due_at LIMIT #{limit} FOR UPDATE OF d SKIP"
          + " LOCKED")
  List<String> candidateDeadlinesSkipLocked(@Param("now") Timestamp now, @Param("limit") int limit);

  @Select(
      "SELECT d.deadline_id FROM aipersimmon_process_deadline d JOIN aipersimmon_process_instance"
          + " i ON i.instance_id = d.instance_id WHERE ((d.status = 'PENDING' AND d.next_attempt_at"
          + " <= #{now}) OR (d.status = 'IN_FLIGHT' AND d.lease_until <= #{now})) AND i.lifecycle"
          + " IN ('RUNNING', 'COMPENSATING') ORDER BY d.due_at LIMIT #{limit}")
  List<String> candidateDeadlines(@Param("now") Timestamp now, @Param("limit") int limit);

  @Update(
      "UPDATE aipersimmon_process_deadline SET status = 'IN_FLIGHT', lease_owner = #{owner},"
          + " lease_token = #{token}, lease_until = #{until}, updated_at = #{now} WHERE deadline_id"
          + " = #{id}")
  void markDeadlineInFlight(
      @Param("id") String id,
      @Param("owner") String owner,
      @Param("token") String token,
      @Param("until") Timestamp until,
      @Param("now") Timestamp now);

  @Update(
      "UPDATE aipersimmon_process_deadline SET status = 'IN_FLIGHT', lease_owner = #{owner},"
          + " lease_token = #{token}, lease_until = #{until}, updated_at = #{now} WHERE deadline_id"
          + " = #{id} AND ((status = 'PENDING' AND next_attempt_at <= #{now}) OR (status ="
          + " 'IN_FLIGHT' AND lease_until <= #{now}))")
  int markDeadlineInFlightIfDue(
      @Param("id") String id,
      @Param("owner") String owner,
      @Param("token") String token,
      @Param("until") Timestamp until,
      @Param("now") Timestamp now);
}
