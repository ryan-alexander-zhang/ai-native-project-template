package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.example.samples.s28.reconciliation.domain.ChunkTally;
import com.example.samples.s28.reconciliation.domain.ImportBatch;
import com.example.samples.s28.reconciliation.domain.ImportBatchId;
import com.example.samples.s28.reconciliation.domain.ImportBatches;
import com.example.samples.s28.reconciliation.domain.ReconciliationErrorCode;
import org.springframework.stereotype.Component;

/** The batch, plus what the receipts say is still outstanding. */
@Component
class ImportBatchQueryHandler implements QueryHandler<ImportBatchQuery, ImportBatchView> {

  private final ImportBatches batches;
  private final ChunkReceipts receipts;

  ImportBatchQueryHandler(ImportBatches batches, ChunkReceipts receipts) {
    this.batches = batches;
    this.receipts = receipts;
  }

  @Override
  public ImportBatchView handle(ImportBatchQuery query) {
    ImportBatchId id = new ImportBatchId(query.batchId());
    ImportBatch batch =
        batches
            .find(id)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        ReconciliationErrorCode.IMPORT_NOT_FOUND, "no import batch " + id));
    ChunkTally tally = receipts.tallyOf(id);
    return new ImportBatchView(
        batch.id().value(),
        batch.declaredChunks(),
        batch.status(),
        tally.missingOf(batch.declaredChunks()),
        tally.rows(),
        batch.acceptedRows(),
        batch.failure().orElse(null),
        batch.openedAt(),
        batch.completedAt().orElse(null));
  }
}
