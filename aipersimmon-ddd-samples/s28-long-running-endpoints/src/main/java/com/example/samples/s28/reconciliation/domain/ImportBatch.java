package com.example.samples.s28.reconciliation.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.time.Instant;
import java.util.Optional;

/**
 * An upload in progress: how many chunks were promised, and whether it has been closed.
 *
 * <p>The chunks are not here — see {@link ChunkTally} for why, and for how the completion rule survives their
 * absence. What is here is the pair of facts that decide whether a chunk may be accepted at all: the batch's
 * status, and the number of chunks it was opened for.
 *
 * <p>Every operation is idempotent, and for an upload that is not a nicety. A resumed upload is a client that
 * lost its connection and does not know how much of what it sent arrived, so it re-sends; if the second
 * attempt at anything is an error, a resume is impossible by construction.
 */
@AggregateRoot
public final class ImportBatch extends AbstractAggregateRoot<ImportBatchId> {

  private final ImportBatchId id;
  private final int declaredChunks;
  private final Instant openedAt;

  private ImportStatus status;
  private long acceptedRows;
  private String failure;
  private Instant completedAt;

  private ImportBatch(
      ImportBatchId id,
      int declaredChunks,
      ImportStatus status,
      long acceptedRows,
      String failure,
      Instant openedAt,
      Instant completedAt) {
    this.id = id;
    this.declaredChunks = declaredChunks;
    this.status = status;
    this.acceptedRows = acceptedRows;
    this.failure = failure;
    this.openedAt = openedAt;
    this.completedAt = completedAt;
  }

  public static ImportBatch open(ImportBatchId id, int declaredChunks, Instant at) {
    if (id == null) {
      throw new IllegalArgumentException("import batch id required");
    }
    if (declaredChunks < 1) {
      throw new IllegalArgumentException("a batch must declare at least one chunk");
    }
    if (at == null) {
      throw new IllegalArgumentException("open time required");
    }
    return new ImportBatch(id, declaredChunks, ImportStatus.OPEN, 0, null, at, null);
  }

  public static ImportBatch reconstitute(
      ImportBatchId id,
      int declaredChunks,
      ImportStatus status,
      long acceptedRows,
      String failure,
      Instant openedAt,
      Instant completedAt,
      long version) {
    ImportBatch batch =
        new ImportBatch(id, declaredChunks, status, acceptedRows, failure, openedAt, completedAt);
    batch.restoreVersion(version);
    return batch;
  }

  /**
   * May a chunk still be stored against this batch?
   *
   * <p>Checked in the aggregate rather than left to the chunk table's primary key, because "the batch is
   * closed" is not something a unique constraint can express — and a chunk landing after completion would
   * sit in the table unaccounted for, which is worse than being refused.
   */
  public void requireOpenFor(int chunkNumber) {
    if (status.isClosed()) {
      throw new DomainException(
          ReconciliationErrorCode.BATCH_CLOSED,
          "batch " + id + " is " + status + "; chunk " + chunkNumber + " arrived too late");
    }
    if (chunkNumber < 1 || chunkNumber > declaredChunks) {
      throw new IllegalArgumentException(
          "batch "
              + id
              + " was opened for "
              + declaredChunks
              + " chunks, so chunk "
              + chunkNumber
              + " is not one of them");
    }
  }

  /**
   * Close the batch, if the tally says everything arrived.
   *
   * @return false if it was already completed, so a client that retries its completion is not told off
   * @throws DomainException naming the missing chunk numbers
   */
  public boolean complete(ChunkTally tally, Instant at) {
    if (status == ImportStatus.COMPLETED) {
      return false;
    }
    if (status == ImportStatus.ABANDONED) {
      throw new DomainException(
          ReconciliationErrorCode.BATCH_CLOSED, "batch " + id + " was abandoned");
    }
    var missing = tally.missingOf(declaredChunks);
    if (!missing.isEmpty()) {
      throw new DomainException(
          ReconciliationErrorCode.CHUNKS_MISSING,
          "batch " + id + " is still missing chunks " + missing);
    }
    this.status = ImportStatus.COMPLETED;
    this.acceptedRows = tally.rows();
    this.completedAt = at;
    return true;
  }

  /** Give up on it, with a reason a client can read. */
  public boolean abandon(String reason, Instant at) {
    if (status.isClosed()) {
      return false;
    }
    this.status = ImportStatus.ABANDONED;
    this.failure = reason;
    this.completedAt = at;
    return true;
  }

  public ImportBatchId id() {
    return id;
  }

  public int declaredChunks() {
    return declaredChunks;
  }

  public ImportStatus status() {
    return status;
  }

  public long acceptedRows() {
    return acceptedRows;
  }

  public Optional<String> failure() {
    return Optional.ofNullable(failure);
  }

  public Instant openedAt() {
    return openedAt;
  }

  public Optional<Instant> completedAt() {
    return Optional.ofNullable(completedAt);
  }
}
