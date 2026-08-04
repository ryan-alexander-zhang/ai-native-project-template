package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s28.reconciliation.domain.ImportBatch;
import com.example.samples.s28.reconciliation.domain.ImportBatchId;
import com.example.samples.s28.reconciliation.domain.ImportBatches;
import com.example.samples.s28.reconciliation.domain.ReconciliationErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Check it, store the receipt, and do not touch the batch.
 *
 * <p>The order is the design. The batch is loaded and asked whether it is still open — a rule — and then
 * <strong>not saved</strong>. Nothing about the batch changed: a chunk arriving is a fact about the chunk table.
 * Saving the aggregate anyway would advance its version on every chunk, which means a thousand-chunk upload
 * would have a thousand chances to collide with the completion, and each collision would be a retry of a
 * transaction that had already written its receipt.
 *
 * <p>That leaves one gap, and it is worth naming rather than hiding: the batch could be completed between the
 * check and the insert, so a chunk can land against a just-closed batch. The completion is what protects
 * against it mattering — it verifies the tally itself, under a version-checked write, so a late chunk cannot
 * make a completed batch wrong. It can only leave one orphaned receipt, which is why the completed batch's
 * accepted-row count comes from the tally it verified rather than from a later count of the table.
 */
@Component
class AcceptChunkHandler implements CommandHandler<AcceptChunk, Boolean> {

  private final ImportBatches batches;
  private final ChunkReceipts receipts;
  private final Clock clock;

  AcceptChunkHandler(ImportBatches batches, ChunkReceipts receipts, Clock clock) {
    this.batches = batches;
    this.receipts = receipts;
    this.clock = clock;
  }

  @Override
  public Boolean handle(AcceptChunk command, CommandContext context) {
    ImportBatchId id = new ImportBatchId(command.batchId());
    ImportBatch batch =
        batches
            .find(id)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        ReconciliationErrorCode.IMPORT_NOT_FOUND, "no import batch " + id));
    batch.requireOpenFor(command.chunkNumber());

    String actual = sha256(command.payload());
    if (!actual.equalsIgnoreCase(command.checksum())) {
      throw new DomainException(
          ReconciliationErrorCode.CHUNK_CORRUPT,
          "chunk "
              + command.chunkNumber()
              + " of batch "
              + id
              + " hashes to "
              + actual
              + ", not to the "
              + command.checksum()
              + " the client declared; it arrived truncated or altered");
    }
    return receipts.record(
        id, command.chunkNumber(), actual, countLines(command.payload()), clock.instant());
  }

  private static int countLines(String payload) {
    if (payload.isEmpty()) {
      return 0;
    }
    return (int) payload.lines().count();
  }

  private static String sha256(String payload) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every JVM", e);
    }
  }
}
