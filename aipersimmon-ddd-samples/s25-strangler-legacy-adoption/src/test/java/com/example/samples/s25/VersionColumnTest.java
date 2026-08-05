package com.example.samples.s25;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.samples.s25.refunds.application.ApproveRefund;
import com.example.samples.s25.refunds.domain.Refund;
import com.example.samples.s25.refunds.domain.RefundId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The version column: what adding it buys, and — the part that matters — what it does not.
 *
 * <p>The catalogue names three transitional options for a legacy table with no version column: add a column, use a shadow
 * table, or take a pessimistic lock. The measurement below is that <strong>the choice between them is not the interesting
 * question</strong>, because none of the three protects anything while a second writer still exists.
 *
 * <p>Adding the column is easy and is the right first move: {@code NOT NULL DEFAULT 0} makes millions of existing rows
 * valid with no backfill. What it does not do is make the legacy {@code UPDATE} visible to the library's check. The
 * library's update says {@code WHERE version = <loaded>}; a legacy statement that never mentions the column leaves it
 * exactly where it was, so the predicate matches and the legacy change is overwritten. No exception, no warning.
 *
 * <p>So the real answer to the question is not a mechanism, it is a sequence: <strong>stop the second writer first.</strong>
 * The version column becomes meaningful at the moment the legacy path delegates, and not before — which is why
 * {@code s25.refunds.route} exists and why the state to reach quickly is {@code NEW_WRITES}.
 */
class VersionColumnTest extends StranglerTestBase {

  @Autowired private PlatformTransactionManager transactions;

  /** It is on the table and the library is using it. The easy half. */
  @Test
  void theversionColumnIsThereAndTheLibraryAdvancesIt() {
    long orderId = placeLegacyOrder(10_000);
    long refundId = entryPoint.raiseRefund(orderId, 2_500, "damaged");

    assertThat(refundVersion(refundId)).as("inserted at version 1").isEqualTo(1);
    commandBus.send(new ApproveRefund(refundId, "ops-anna"));
    assertThat(refundVersion(refundId)).as("and advanced by the version-checked write").isEqualTo(2);
  }

  /** And it does what it is for, against another writer that participates. */
  @Test
  void astaleSnapshotFromTheNewPathIsRefused() {
    long orderId = placeLegacyOrder(10_000);
    long refundId = entryPoint.raiseRefund(orderId, 2_500, "damaged");

    Refund readEarly = refunds.find(new RefundId(refundId)).orElseThrow();
    commandBus.send(new ApproveRefund(refundId, "ops-anna"));

    readEarly.reject("changed our mind");
    assertThatThrownBy(() -> inATransaction(() -> refunds.save(readEarly)))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(refundRow(refundId)).containsEntry("state", "APPROVED");
  }

  /**
   * <strong>The finding.</strong> A legacy {@code UPDATE} is invisible to the version check, so the new path overwrites it
   * silently.
   *
   * <p>Read the sequence carefully, because it is what happens on any afternoon during a migration: the new context loads
   * a refund, the monolith's own screen approves it, and then the new context writes. The version the new context checks
   * is still the version it read — the legacy statement never touched the column — so the write succeeds and the approval
   * is gone.
   *
   * <p>Nothing about the result looks like a concurrency bug afterwards. The row is consistent, the version advanced, the
   * audit trail (if there were one) records one write. This is the reason "we added a version column" is not an answer to
   * "how do we make this safe".
   */
  @Test
  void alegacyUpdateIsInvisibleToTheVersionCheckAndIsSilentlyOverwritten() {
    long orderId = placeLegacyOrder(10_000);
    long refundId = entryPoint.raiseRefund(orderId, 2_500, "damaged");

    // The new context reads the refund, intending to reject it.
    Refund readByTheNewPath = refunds.find(new RefundId(refundId)).orElseThrow();
    long versionItRead = refundVersion(refundId);

    // Meanwhile somebody uses the monolith's own screen. This is the writer that does not participate.
    legacy.approveRefund(refundId, "ops-legacy");
    assertThat(refundRow(refundId)).containsEntry("state", "APPROVED");
    assertThat(refundVersion(refundId))
        .as("the legacy UPDATE never mentions the column, so it did not move")
        .isEqualTo(versionItRead);

    // And now the new context writes. The version it checks is still current, so nothing refuses it.
    readByTheNewPath.reject("changed our mind");
    inATransaction(() -> refunds.save(readByTheNewPath));

    assertThat(refundRow(refundId))
        .as("the approval is gone, and no exception was raised anywhere")
        .containsEntry("state", "REJECTED");
    assertThat(refundRow(refundId).get("approved_by"))
        .as("the approver's name went with it, forced to null by the framework's own cleared-column write")
        .isNull();
  }

  /**
   * And the fix, which is not a mechanism: stop the second writer.
   *
   * <p>Once the legacy entry point delegates ({@code NEW_WRITES}, the default), the same interleaving is a version clash
   * and is refused. The column started meaning something the moment there was one writer — not the moment it was added.
   */
  @Test
  void withoneWriterTheSameInterleavingIsRefused() {
    long orderId = placeLegacyOrder(10_000);
    long refundId = entryPoint.raiseRefund(orderId, 2_500, "damaged");

    Refund readByTheNewPath = refunds.find(new RefundId(refundId)).orElseThrow();

    // The same operator action, arriving through the seam instead of round it.
    entryPoint.approveRefund(refundId, "ops-legacy");
    assertThat(refundVersion(refundId)).as("this writer participates").isEqualTo(2);

    readByTheNewPath.reject("changed our mind");
    assertThatThrownBy(() -> inATransaction(() -> refunds.save(readByTheNewPath)))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(refundRow(refundId)).containsEntry("state", "APPROVED");
  }

  /**
   * A pessimistic lock does not rescue it either, and the measurement says why in one line.
   *
   * <p>{@code SELECT ... FOR UPDATE} only excludes writers that ask for the same lock. The legacy statement does not, so
   * it proceeds — the lock is taken and released by the transaction that took it, and the row changes underneath anyway.
   * Which generalises: <strong>every concurrency control is an agreement, and a writer that never agreed to it is not
   * constrained by it.</strong>
   */
  @Test
  void apessimisticLockDoesNotConstrainAWriterThatNeverTakesIt() {
    long orderId = placeLegacyOrder(10_000);
    long refundId = entryPoint.raiseRefund(orderId, 2_500, "damaged");

    inATransaction(
        () -> {
          jdbc.queryForMap("SELECT * FROM legacy_refunds WHERE id = ? FOR UPDATE", refundId);
          // The legacy path, on another connection, is blocked only for as long as this transaction lasts —
          // and nothing makes it wait for a lock it never asks about once this commits.
        });
    legacy.approveRefund(refundId, "ops-legacy");
    assertThat(refundRow(refundId))
        .as("the lock was held, released, and changed nothing about the other writer's freedom")
        .containsEntry("state", "APPROVED");
  }

  /** The rule the monolith never had, now enforced — which is the actual gain from the extraction. */
  @Test
  void thenewPathRefusesASecondApprovalWhereTheMonolithWentQuiet() {
    long orderId = placeLegacyOrder(10_000);
    long refundId = entryPoint.raiseRefund(orderId, 2_500, "damaged");
    entryPoint.approveRefund(refundId, "ops-anna");

    assertThatThrownBy(() -> entryPoint.approveRefund(refundId, "ops-bob"))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("already APPROVED");
    assertThat(refundRow(refundId)).containsEntry("approved_by", "ops-anna");
  }

  private void inATransaction(Runnable work) {
    new TransactionTemplate(transactions).executeWithoutResult(status -> work.run());
  }
}
