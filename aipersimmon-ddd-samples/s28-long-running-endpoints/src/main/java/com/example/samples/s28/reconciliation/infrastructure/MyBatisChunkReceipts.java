package com.example.samples.s28.reconciliation.infrastructure;

import com.example.samples.s28.reconciliation.application.ChunkReceipts;
import com.example.samples.s28.reconciliation.domain.ChunkTally;
import com.example.samples.s28.reconciliation.domain.ImportBatchId;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Receipts, straight over the chunk table. */
@Component
class MyBatisChunkReceipts implements ChunkReceipts {

  private final ChunkReceiptMapper mapper;

  MyBatisChunkReceipts(ChunkReceiptMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public boolean record(
      ImportBatchId batchId, int chunkNumber, String checksum, int rowCount, Instant at) {
    return mapper.recordIfAbsent(
            batchId.value(), chunkNumber, checksum, rowCount, Timestamp.from(at))
        == 1;
  }

  @Override
  public ChunkTally tallyOf(ImportBatchId batchId) {
    return ChunkTally.of(mapper.chunkNumbers(batchId.value()), mapper.totalRows(batchId.value()));
  }
}
