package com.example.samples.s28.reconciliation.infrastructure;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Chunk receipts: one conditional insert and two reads. */
@Mapper
interface ChunkReceiptMapper {

  /**
   * Record a chunk, unless it is already recorded.
   *
   * <p>{@code ON CONFLICT DO NOTHING} rather than a select followed by an insert, because the two attempts this
   * has to tolerate are precisely the ones that arrive at the same moment — a client re-sending a chunk it thinks
   * was lost, while the original is still in flight. A check-then-insert would let both through and then have one
   * of them fail on the key, turning an expected retry into a 500.
   *
   * <p>The library's own {@code saveAggregate} refuses to be built this way, and the difference is worth being
   * clear about rather than treating one of them as inconsistent: an aggregate insert that swallows a conflict has
   * hidden a genuine identity clash, so it must fail loudly. A chunk receipt's conflict <em>is</em> the expected
   * case. Same statement, opposite meaning, and the meaning comes from what the key represents.
   *
   * @return 1 if it was recorded now, 0 if it was already there
   */
  @Insert(
      "INSERT INTO s28_import_chunk (batch_id, chunk_no, checksum, row_count, received_at)"
          + " VALUES (#{batchId}, #{chunkNo}, #{checksum}, #{rowCount}, #{at})"
          + " ON CONFLICT (batch_id, chunk_no) DO NOTHING")
  int recordIfAbsent(
      @Param("batchId") String batchId,
      @Param("chunkNo") int chunkNo,
      @Param("checksum") String checksum,
      @Param("rowCount") int rowCount,
      @Param("at") Timestamp at);

  @Select("SELECT chunk_no FROM s28_import_chunk WHERE batch_id = #{batchId} ORDER BY chunk_no")
  List<Integer> chunkNumbers(@Param("batchId") String batchId);

  @Select("SELECT COALESCE(SUM(row_count), 0) FROM s28_import_chunk WHERE batch_id = #{batchId}")
  long totalRows(@Param("batchId") String batchId);
}
