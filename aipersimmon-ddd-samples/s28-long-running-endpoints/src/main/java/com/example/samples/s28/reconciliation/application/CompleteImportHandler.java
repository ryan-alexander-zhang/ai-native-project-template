package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s28.reconciliation.domain.ChunkTally;
import com.example.samples.s28.reconciliation.domain.ImportBatch;
import com.example.samples.s28.reconciliation.domain.ImportBatchId;
import com.example.samples.s28.reconciliation.domain.ImportBatches;
import com.example.samples.s28.reconciliation.domain.ReconciliationErrorCode;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** Count what arrived, hand the count to the batch, let it decide. */
@Component
class CompleteImportHandler implements CommandHandler<CompleteImport, Boolean> {

  private final ImportBatches batches;
  private final ChunkReceipts receipts;
  private final Clock clock;

  CompleteImportHandler(ImportBatches batches, ChunkReceipts receipts, Clock clock) {
    this.batches = batches;
    this.receipts = receipts;
    this.clock = clock;
  }

  @Override
  public Boolean handle(CompleteImport command, CommandContext context) {
    ImportBatchId id = new ImportBatchId(command.batchId());
    ImportBatch batch =
        batches
            .find(id)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        ReconciliationErrorCode.IMPORT_NOT_FOUND, "no import batch " + id));
    ChunkTally tally = receipts.tallyOf(id);
    if (!batch.complete(tally, clock.instant())) {
      return false;
    }
    batches.save(batch);
    return true;
  }
}
