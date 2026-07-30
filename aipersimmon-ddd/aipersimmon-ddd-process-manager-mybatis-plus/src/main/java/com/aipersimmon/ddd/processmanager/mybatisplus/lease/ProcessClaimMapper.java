package com.aipersimmon.ddd.processmanager.mybatisplus.lease;

import com.aipersimmon.ddd.processmanager.engine.lease.ProcessClaimSql;
import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Claim SQL for effects and deadlines, in a {@code SKIP LOCKED} variant (PostgreSQL/MySQL) and a
 * plain-candidate + atomic-conditional-{@code UPDATE} variant (H2).
 *
 * <p>Which rows are claimable, and in what order, comes from {@link ProcessClaimSql} — the same
 * constants the JDBC backend uses. Only the concurrency mechanism and the {@code LIMIT} differ, and
 * those are all that should ever be appended here.
 */
public interface ProcessClaimMapper {

  @Select(ProcessClaimSql.EFFECT_CANDIDATE + " LIMIT #{limit} FOR UPDATE SKIP LOCKED")
  List<String> candidateEffectsSkipLocked(@Param("now") Timestamp now, @Param("limit") int limit);

  @Select(ProcessClaimSql.EFFECT_CANDIDATE + " LIMIT #{limit}")
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

  @Select(ProcessClaimSql.DEADLINE_CANDIDATE + " LIMIT #{limit} FOR UPDATE OF d SKIP LOCKED")
  List<String> candidateDeadlinesSkipLocked(@Param("now") Timestamp now, @Param("limit") int limit);

  @Select(ProcessClaimSql.DEADLINE_CANDIDATE + " LIMIT #{limit}")
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
