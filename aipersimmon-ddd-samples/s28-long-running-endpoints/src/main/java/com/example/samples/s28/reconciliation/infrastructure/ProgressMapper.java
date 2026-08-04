package com.example.samples.s28.reconciliation.infrastructure;

import java.sql.Timestamp;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Progress, in three statements and no entity class.
 *
 * <p>No {@code BaseMapper}, no row type, no {@code @Version}: there is nothing here for MyBatis-Plus to help
 * with. One upsert, one read, one delete — and writing them out is shorter than the entity that would let the
 * generated CRUD express the same thing.
 */
@Mapper
interface ProgressMapper {

  /**
   * Publish a reading, overwriting the previous one.
   *
   * <p>An upsert rather than insert-or-update, because two ticks of the same job can only overlap if a worker was
   * superseded mid-run, and in that case the last writer is the right one. {@code GREATEST} is deliberately not
   * used: a superseded worker's stale high-water mark should not outrank the new owner's honest low one.
   */
  @Update(
      "INSERT INTO s28_export_progress (job_id, rows_done, rows_total, updated_at)"
          + " VALUES (#{jobId}, #{rowsDone}, #{rowsTotal}, #{at})"
          + " ON CONFLICT (job_id) DO UPDATE SET rows_done = EXCLUDED.rows_done,"
          + " rows_total = EXCLUDED.rows_total, updated_at = EXCLUDED.updated_at")
  void upsert(
      @Param("jobId") String jobId,
      @Param("rowsDone") long rowsDone,
      @Param("rowsTotal") Long rowsTotal,
      @Param("at") Timestamp at);

  @Select(
      "SELECT rows_done AS rowsDone, rows_total AS rowsTotal, updated_at AS updatedAt"
          + " FROM s28_export_progress WHERE job_id = #{jobId}")
  ProgressReading read(@Param("jobId") String jobId);

  @Delete("DELETE FROM s28_export_progress WHERE job_id = #{jobId}")
  void delete(@Param("jobId") String jobId);
}
