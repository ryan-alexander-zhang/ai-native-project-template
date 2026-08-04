package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s28.reconciliation.domain.ImportBatch;
import com.example.samples.s28.reconciliation.domain.ImportBatchId;
import com.example.samples.s28.reconciliation.domain.ImportBatches;
import com.example.samples.s28.reconciliation.domain.ReconciliationErrorCode;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Open it, or recognise the batch that is already open — same three outcomes as {@code SubmitExportHandler}. */
@Component
class OpenImportHandler implements CommandHandler<OpenImport, Boolean> {

  private final ImportBatches batches;
  private final Clock clock;

  OpenImportHandler(ImportBatches batches, Clock clock) {
    this.batches = batches;
    this.clock = clock;
  }

  @Override
  public Boolean handle(OpenImport command, CommandContext context) {
    ImportBatchId id = new ImportBatchId(command.batchId());
    Optional<ImportBatch> existing = batches.find(id);
    if (existing.isPresent()) {
      if (existing.get().declaredChunks() != command.chunks()) {
        throw new DomainException(
            ReconciliationErrorCode.REQUEST_MISMATCH,
            "batch "
                + id
                + " was opened for "
                + existing.get().declaredChunks()
                + " chunks, so it cannot be re-opened for "
                + command.chunks());
      }
      return false;
    }
    batches.save(ImportBatch.open(id, command.chunks(), clock.instant()));
    return true;
  }
}
