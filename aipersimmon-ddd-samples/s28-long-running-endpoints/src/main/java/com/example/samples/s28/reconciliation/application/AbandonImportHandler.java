package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s28.reconciliation.domain.ImportBatch;
import com.example.samples.s28.reconciliation.domain.ImportBatchId;
import com.example.samples.s28.reconciliation.domain.ImportBatches;
import com.example.samples.s28.reconciliation.domain.ReconciliationErrorCode;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** Close it the other way. */
@Component
class AbandonImportHandler implements CommandHandler<AbandonImport, Boolean> {

  private final ImportBatches batches;
  private final Clock clock;

  AbandonImportHandler(ImportBatches batches, Clock clock) {
    this.batches = batches;
    this.clock = clock;
  }

  @Override
  public Boolean handle(AbandonImport command, CommandContext context) {
    ImportBatchId id = new ImportBatchId(command.batchId());
    ImportBatch batch =
        batches
            .find(id)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        ReconciliationErrorCode.IMPORT_NOT_FOUND, "no import batch " + id));
    if (!batch.abandon(command.reason(), clock.instant())) {
      return false;
    }
    batches.save(batch);
    return true;
  }
}
