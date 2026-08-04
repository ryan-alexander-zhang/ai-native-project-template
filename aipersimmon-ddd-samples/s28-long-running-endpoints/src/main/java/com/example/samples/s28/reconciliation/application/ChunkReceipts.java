package com.example.samples.s28.reconciliation.application;

import com.example.samples.s28.reconciliation.domain.ChunkTally;
import com.example.samples.s28.reconciliation.domain.ImportBatchId;
import java.time.Instant;

/**
 * What has arrived, kept outside the batch aggregate for the reasons set out on {@link ChunkTally}.
 *
 * <p>This is where a resumable upload actually lives. The client's question after a dropped connection is not
 * "did it work" but "what do I still have to send", and the only honest source for that is the receipts.
 */
public interface ChunkReceipts {

  /**
   * Record that a chunk arrived.
   *
   * @return false if it was already on record, which is what makes re-sending a chunk free rather than an error
   */
  boolean record(
      ImportBatchId batchId, int chunkNumber, String checksum, int rowCount, Instant at);

  /** Everything on record for the batch. */
  ChunkTally tallyOf(ImportBatchId batchId);
}
