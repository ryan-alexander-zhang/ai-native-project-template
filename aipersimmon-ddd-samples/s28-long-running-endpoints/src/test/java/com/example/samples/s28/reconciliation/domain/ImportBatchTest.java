package com.example.samples.s28.reconciliation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.core.exception.DomainException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The batch's rules, and the tally that keeps the receipts out of it. */
class ImportBatchTest {

  private static final ImportBatchId ID = new ImportBatchId("imp-1");
  private static final Instant NOW = Instant.parse("2026-07-01T10:00:00Z");

  @Test
  void abatchIsOpenForExactlyTheChunksItDeclared() {
    ImportBatch batch = ImportBatch.open(ID, 3, NOW);
    batch.requireOpenFor(1);
    batch.requireOpenFor(3);
    assertThatThrownBy(() -> batch.requireOpenFor(4))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("was opened for 3 chunks");
  }

  @Test
  void completionNamesWhatIsMissing() {
    ImportBatch batch = ImportBatch.open(ID, 4, NOW);
    assertThatThrownBy(() -> batch.complete(ChunkTally.of(List.of(1, 3), 20), NOW))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("missing chunks [2, 4]")
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .contains(ReconciliationErrorCode.CHUNKS_MISSING));
  }

  @Test
  void completionTakesItsRowCountFromTheTallyItVerified() {
    ImportBatch batch = ImportBatch.open(ID, 2, NOW);
    assertThat(batch.complete(ChunkTally.of(List.of(1, 2), 512), NOW)).isTrue();
    assertThat(batch.status()).isEqualTo(ImportStatus.COMPLETED);
    assertThat(batch.acceptedRows()).isEqualTo(512);
  }

  /** A resumed client may well complete twice; the second one must not be an error. */
  @Test
  void completingTwiceIsNotAnError() {
    ImportBatch batch = ImportBatch.open(ID, 1, NOW);
    ChunkTally tally = ChunkTally.of(List.of(1), 10);
    assertThat(batch.complete(tally, NOW)).isTrue();
    assertThat(batch.complete(tally, NOW)).isFalse();
    assertThat(batch.acceptedRows()).isEqualTo(10);
  }

  @Test
  void achunkArrivingAfterCompletionIsRefused() {
    ImportBatch batch = ImportBatch.open(ID, 1, NOW);
    batch.complete(ChunkTally.of(List.of(1), 10), NOW);
    assertThatThrownBy(() -> batch.requireOpenFor(1))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .contains(ReconciliationErrorCode.BATCH_CLOSED));
  }

  @Test
  void anabandonedBatchCannotBeCompletedAfterAll() {
    ImportBatch batch = ImportBatch.open(ID, 1, NOW);
    assertThat(batch.abandon("the client gave up", NOW)).isTrue();
    assertThat(batch.abandon("again", NOW)).isFalse();
    assertThatThrownBy(() -> batch.complete(ChunkTally.of(List.of(1), 10), NOW))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("was abandoned");
  }

  @Test
  void thetallyKnowsWhichNumbersAreOutstanding() {
    assertThat(ChunkTally.of(List.of(2, 5), 0).missingOf(5)).containsExactly(1, 3, 4);
    assertThat(ChunkTally.of(List.of(1, 2, 3), 0).missingOf(3)).isEmpty();
  }
}
