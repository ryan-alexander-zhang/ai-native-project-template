package com.example.samples.s28.reconciliation.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * The job table's mapper: the generated CRUD, one scalar read, and the two statements that make up a claim.
 *
 * <p>The claim is two statements rather than one {@code UPDATE ... RETURNING} because that is the shape the
 * library uses for its own relays, and the reason it gives is portability: {@code FOR UPDATE SKIP LOCKED} plus a
 * conditional {@code UPDATE} works on PostgreSQL and MySQL alike, and degrades to a plain conditional update
 * where {@code SKIP LOCKED} does not exist. Both statements run in one transaction; the row locks the select
 * takes are what keep two workers from choosing the same candidate.
 */
@Mapper
interface ExportJobMapper extends BaseMapper<ExportJobRow> {

  /**
   * The candidates: queued jobs oldest first, plus running jobs whose lease has lapsed.
   *
   * <p>The second half is the whole recovery story. A worker that was killed released nothing — it could not —
   * so its job stays RUNNING with a lease that stops being extended, and this predicate picks it up when the
   * lease runs out. No supervisor, no health check, no operator.
   */
  @Select(
      "SELECT id FROM s28_export_job"
          + " WHERE status = 'QUEUED' OR (status = 'RUNNING' AND lease_until <= #{now})"
          + " ORDER BY submitted_at LIMIT #{limit} FOR UPDATE SKIP LOCKED")
  List<String> claimCandidates(@Param("now") Timestamp now, @Param("limit") int limit);

  /**
   * Take it, if it is still takeable.
   *
   * <p>The {@code WHERE} clause repeats the candidate predicate rather than trusting the select, because between
   * the two statements the world may have moved — and re-checking costs nothing while assuming costs a job run
   * twice.
   *
   * <p><strong>{@code version = version + 1} is not bookkeeping.</strong> It is what allows this statement to
   * coexist with the version-checked aggregate writes on the same table. Without it a cancellation that read the
   * job a moment ago still commits — it checks the version it read, and that version is still current — and the
   * job ends CANCELLED while this worker runs it to completion. Nothing about that outcome looks like a
   * concurrency bug afterwards; it looks like a cancellation that worked.
   *
   * @return 1 if the claim was taken, 0 if somebody else got there first
   */
  @Update(
      "UPDATE s28_export_job SET status = 'RUNNING', lease_owner = #{owner},"
          + " lease_until = #{until}, attempt = attempt + 1, started_at = #{now},"
          + " version = version + 1"
          + " WHERE id = #{id}"
          + " AND (status = 'QUEUED' OR (status = 'RUNNING' AND lease_until <= #{now}))")
  int claim(
      @Param("id") String id,
      @Param("owner") String owner,
      @Param("until") Timestamp until,
      @Param("now") Timestamp now);

  /**
   * Push the lease forward. Note the absence of {@code version = version + 1} — see {@code ExportClaims}: a
   * heartbeat that advanced the version would fence the worker against its own loaded aggregate.
   *
   * @return 1 while the claim is still ours, 0 once it is not
   */
  @Update(
      "UPDATE s28_export_job SET lease_until = #{until}"
          + " WHERE id = #{id} AND lease_owner = #{owner} AND status = 'RUNNING'")
  int heartbeat(
      @Param("id") String id, @Param("owner") String owner, @Param("until") Timestamp until);

  /** One column, for the cancellation check the runner makes once per progress interval. */
  @Select("SELECT cancel_requested FROM s28_export_job WHERE id = #{id}")
  Boolean readCancelRequested(@Param("id") String id);
}
