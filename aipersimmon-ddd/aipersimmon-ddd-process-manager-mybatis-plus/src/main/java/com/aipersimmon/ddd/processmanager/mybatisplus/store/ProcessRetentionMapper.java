package com.aipersimmon.ddd.processmanager.mybatisplus.store;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MyBatis mapper for retention. The SQL mirrors {@code JdbcProcessRetentionStore}
 * statement-for-statement; only positional {@code ?} placeholders become named {@code #{}}
 * parameters and the id list an {@code <foreach>}.
 */
public interface ProcessRetentionMapper {

  /**
   * Ended, retention elapsed, and nothing still owed.
   *
   * <p>Ordered by {@code instance_id} within a timestamp so the order is total; with a batch limit
   * an unstable order could leave an instance behind a tie never reached.
   *
   * <p>The two {@code NOT EXISTS} clauses are the policy. {@code PENDING} and {@code IN_FLIGHT}
   * keep an instance because a terminal decision's staged effects still deliver after it ends;
   * {@code DEAD} keeps it because that row is the record of a side effect that never landed and an
   * operator can still redrive it. Everything else — delivered, fired, cancelled — is settled.
   */
  @Select(
      "SELECT i.instance_id FROM aipersimmon_process_instance i"
          + " WHERE i.lifecycle IN ('COMPLETED', 'FAILED', 'CANCELLED')"
          + " AND i.updated_at < #{endedBefore}"
          + " AND NOT EXISTS ("
          + "     SELECT 1 FROM aipersimmon_process_effect e"
          + "     WHERE e.instance_id = i.instance_id"
          + "       AND e.status IN ('PENDING', 'IN_FLIGHT', 'DEAD'))"
          + " AND NOT EXISTS ("
          + "     SELECT 1 FROM aipersimmon_process_deadline d"
          + "     WHERE d.instance_id = i.instance_id"
          + "       AND d.status IN ('PENDING', 'IN_FLIGHT', 'DEAD'))"
          + " ORDER BY i.updated_at, i.instance_id LIMIT #{limit}")
  List<String> findPurgeable(
      @Param("endedBefore") Timestamp endedBefore, @Param("limit") int limit);

  @Delete(
      "<script>DELETE FROM aipersimmon_process_effect WHERE instance_id IN"
          + "<foreach item='id' collection='instanceIds' open='(' separator=',' close=')'>"
          + "#{id}</foreach></script>")
  int deleteEffects(@Param("instanceIds") List<String> instanceIds);

  @Delete(
      "<script>DELETE FROM aipersimmon_process_deadline WHERE instance_id IN"
          + "<foreach item='id' collection='instanceIds' open='(' separator=',' close=')'>"
          + "#{id}</foreach></script>")
  int deleteDeadlines(@Param("instanceIds") List<String> instanceIds);

  @Delete(
      "<script>DELETE FROM aipersimmon_process_transition WHERE instance_id IN"
          + "<foreach item='id' collection='instanceIds' open='(' separator=',' close=')'>"
          + "#{id}</foreach></script>")
  int deleteTransitions(@Param("instanceIds") List<String> instanceIds);

  @Delete(
      "<script>DELETE FROM aipersimmon_process_instance WHERE instance_id IN"
          + "<foreach item='id' collection='instanceIds' open='(' separator=',' close=')'>"
          + "#{id}</foreach></script>")
  int deleteInstances(@Param("instanceIds") List<String> instanceIds);
}
