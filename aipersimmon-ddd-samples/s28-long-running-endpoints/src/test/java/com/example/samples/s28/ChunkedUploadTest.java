package com.example.samples.s28;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.samples.s28.reconciliation.application.AbandonImport;
import com.example.samples.s28.reconciliation.application.AcceptChunk;
import com.example.samples.s28.reconciliation.application.CompleteImport;
import com.example.samples.s28.reconciliation.application.ImportBatchQuery;
import com.example.samples.s28.reconciliation.application.ImportBatchView;
import com.example.samples.s28.reconciliation.application.OpenImport;
import com.example.samples.s28.reconciliation.domain.ImportStatus;
import com.example.samples.s28.reconciliation.domain.ReconciliationErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * A resumable upload, and the property that makes it resumable: every request can be repeated.
 *
 * <p>A resume is by definition a client that does not know how much of what it sent arrived — that is what the
 * failure destroyed. So the server has to answer "what do you still want", and every verb has to tolerate being
 * replayed. Neither is an optimisation: without them the only recovery from a dropped connection is to send the whole
 * file again, which for the file sizes that made an upload chunked in the first place is not a recovery.
 */
class ChunkedUploadTest extends ReconciliationTestBase {

  private static final String BATCH = "imp-june";

  @Test
  void openingaBatchTwiceIsTheSameBatch() {
    assertThat(commandBus.send(new OpenImport(BATCH, 3))).isTrue();
    assertThat(commandBus.send(new OpenImport(BATCH, 3))).as("a retry, not a second batch").isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM s28_import_batch", Long.class)).isEqualTo(1);
  }

  /** The same {@code Mismatch} verdict S2's idempotency store gives, expressed by the resource itself. */
  @Test
  void reopeningaBatchForADifferentChunkCountIsRefused() {
    commandBus.send(new OpenImport(BATCH, 3));
    assertThatThrownBy(() -> commandBus.send(new OpenImport(BATCH, 4)))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .contains(ReconciliationErrorCode.REQUEST_MISMATCH));
  }

  @Test
  void chunksMayArriveOutOfOrderAndTwice() {
    commandBus.send(new OpenImport(BATCH, 3));
    assertThat(send(3, "c\nc\n")).isTrue();
    assertThat(send(1, "a\n")).isTrue();
    assertThat(send(3, "c\nc\n")).as("already on record, and that is not an error").isFalse();
    assertThat(view().missingChunks()).containsExactly(2);
    assertThat(view().receivedRows()).as("counted once").isEqualTo(3);
  }

  /** The resume endpoint. The server's answer, because the client's record is what the failure destroyed. */
  @Test
  void theserverSaysWhichChunksItStillWants() {
    commandBus.send(new OpenImport(BATCH, 5));
    send(1, "a\n");
    send(4, "d\n");
    ImportBatchView view = view();
    assertThat(view.missingChunks()).containsExactly(2, 3, 5);
    assertThat(view.status()).isEqualTo(ImportStatus.OPEN);
  }

  @Test
  void completionIsRefusedUntilEveryChunkIsThereAndSaysWhichAreNot() {
    commandBus.send(new OpenImport(BATCH, 3));
    send(1, "a\n");
    assertThatThrownBy(() -> commandBus.send(new CompleteImport(BATCH)))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("missing chunks [2, 3]");
    assertThat(view().status()).isEqualTo(ImportStatus.OPEN);
  }

  @Test
  void completionIsIdempotentAndCountsTheRowsOnce() {
    commandBus.send(new OpenImport(BATCH, 2));
    send(1, "a\nb\n");
    send(2, "c\n");
    assertThat(commandBus.send(new CompleteImport(BATCH))).isTrue();
    assertThat(commandBus.send(new CompleteImport(BATCH))).isFalse();
    assertThat(view().acceptedRows()).isEqualTo(3);
    assertThat(view().status()).isEqualTo(ImportStatus.COMPLETED);
  }

  /**
   * A truncated chunk is the ordinary failure of a dropped connection, and it is the one the primary key cannot
   * catch: "we have chunk 7" and "it is the chunk 7 you meant" are different claims.
   */
  @Test
  void achunkWhoseBytesDoNotMatchItsChecksumIsRefusedAndNotRecorded() {
    commandBus.send(new OpenImport(BATCH, 2));
    assertThatThrownBy(
            () -> commandBus.send(new AcceptChunk(BATCH, 1, sha256("a\nb\n"), "a\n")))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .contains(ReconciliationErrorCode.CHUNK_CORRUPT));
    assertThat(view().missingChunks()).containsExactly(1, 2);
  }

  @Test
  void achunkOutsideTheDeclaredRangeIsRefused() {
    commandBus.send(new OpenImport(BATCH, 2));
    assertThatThrownBy(() -> send(3, "c\n"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("was opened for 2 chunks");
  }

  @Test
  void achunkArrivingAfterCompletionIsRefused() {
    commandBus.send(new OpenImport(BATCH, 1));
    send(1, "a\n");
    commandBus.send(new CompleteImport(BATCH));
    assertThatThrownBy(() -> send(1, "a\n"))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .contains(ReconciliationErrorCode.BATCH_CLOSED));
  }

  /** Without this, an abandoned upload is indistinguishable from one whose client is about to come back. */
  @Test
  void anabandonedBatchIsClosedWithAReason() {
    commandBus.send(new OpenImport(BATCH, 3));
    send(1, "a\n");
    assertThat(commandBus.send(new AbandonImport(BATCH, "the client gave up"))).isTrue();
    assertThat(commandBus.send(new AbandonImport(BATCH, "again"))).isFalse();
    ImportBatchView view = view();
    assertThat(view.status()).isEqualTo(ImportStatus.ABANDONED);
    assertThat(view.failure()).isEqualTo("the client gave up");
    assertThatThrownBy(() -> send(2, "b\n")).isInstanceOf(DomainException.class);
  }

  /**
   * The receipts do not belong to the batch, and this is the number that says why.
   *
   * <p>Twenty chunks, and the batch has been written exactly once — at open. Had the receipts been a child collection
   * of the aggregate, each would have been a version-checked save of the root plus a rewrite of every receipt so far.
   */
  @Test
  void twentyChunksLeaveTheBatchAggregateUntouched() {
    commandBus.send(new OpenImport(BATCH, 20));
    long versionAfterOpening = batchVersion();
    for (int n = 1; n <= 20; n++) {
      send(n, "row-" + n + "\n");
    }
    assertThat(batchVersion()).isEqualTo(versionAfterOpening);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM s28_import_chunk", Long.class))
        .isEqualTo(20);
  }

  private boolean send(int chunkNumber, String payload) {
    return commandBus.send(new AcceptChunk(BATCH, chunkNumber, sha256(payload), payload));
  }

  private ImportBatchView view() {
    return queryBus.ask(new ImportBatchQuery(BATCH));
  }

  private long batchVersion() {
    return jdbc.queryForObject(
        "SELECT version FROM s28_import_batch WHERE id = ?", Long.class, BATCH);
  }

  private static String sha256(String payload) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
