package com.example.samples.s28;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.samples.s28.reconciliation.application.ExportJobQuery;
import com.example.samples.s28.reconciliation.application.ExportJobView;
import com.example.samples.s28.reconciliation.application.ExportRunner;
import com.example.samples.s28.reconciliation.application.ReportExportOutcome;
import com.example.samples.s28.reconciliation.application.RetryExport;
import com.example.samples.s28.reconciliation.domain.Artifact;
import com.example.samples.s28.reconciliation.domain.ExportJob;
import com.example.samples.s28.reconciliation.domain.ExportJobId;
import com.example.samples.s28.reconciliation.domain.ExportStatus;
import com.example.samples.s28.reconciliation.domain.ReconciliationErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a failed job looks like from outside, and the two races a claimed job has that a synchronous handler does not.
 *
 * <p>The lease is what makes a killed worker recoverable without anybody noticing it died — it released nothing,
 * because it could not, so the claim simply runs out. The fence is what stops that same worker from coming back and
 * reporting an outcome for work somebody else has since redone. They are different mechanisms and both are needed.
 */
class FailureVisibilityTest extends ReconciliationTestBase {

  @Autowired private PlatformTransactionManager transactions;

  @Test
  void afailedExportSaysWhyAndCountsTheAttempt() {
    seed(PERIOD, 50);
    submit("exp-fail");
    ExportJobId claimed = claimAs("worker-a");
    jdbc.execute("ALTER TABLE s28_export_row RENAME TO s28_export_row_hidden");
    try {
      assertThat(runner.run(claimed, "worker-a")).isEqualTo(ExportRunner.Outcome.FAILED);
    } finally {
      jdbc.execute("ALTER TABLE s28_export_row_hidden RENAME TO s28_export_row");
    }
    ExportJobView view = queryBus.ask(new ExportJobQuery("exp-fail"));
    assertThat(view.status()).isEqualTo(ExportStatus.FAILED);
    assertThat(view.attempt()).isEqualTo(1);
    assertThat(view.failure()).isNotBlank();
    assertThat(view.contentPath()).as("nothing to download").isNull();
  }

  @Test
  void aretryIsANewAttemptOnTheSameJob() {
    seed(PERIOD, 50);
    submit("exp-retry");
    ExportJobId claimed = claimAs("worker-a");
    commandBus.send(
        ReportExportOutcome.failed("exp-retry", "worker-a", "the partner's SFTP was down"));

    commandBus.send(new RetryExport("exp-retry"));
    assertThat(jobRow("exp-retry").get("status")).isEqualTo("QUEUED");
    assertThat(jobRow("exp-retry").get("attempt")).isEqualTo(1);

    ExportJobId again = claimAs("worker-b");
    assertThat(again).isEqualTo(claimed);
    assertThat(runner.run(again, "worker-b")).isEqualTo(ExportRunner.Outcome.SUCCEEDED);
    assertThat(queryBus.ask(new ExportJobQuery("exp-retry")).attempt())
        .as("two attempts, and the history is not rewritten by the one that worked")
        .isEqualTo(2);
  }

  @Test
  void asucceededExportCannotBeRetried() {
    seed(PERIOD, 10);
    submit("exp-done");
    ExportJobId claimed = claimAs("worker-a");
    runner.run(claimed, "worker-a");
    assertThatThrownBy(() -> commandBus.send(new RetryExport("exp-done")))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .contains(ReconciliationErrorCode.EXPORT_NOT_RETRYABLE));
  }

  /** While the lease holds, nobody else can have it — not even after the worker has stopped existing. */
  @Test
  void arunningJobIsNotClaimableUntilItsLeaseLapses() {
    seed(PERIOD, 10);
    submit("exp-leased");
    claimAs("worker-a");

    assertThat(claims.claimNext("worker-b", settings.getLease(), Instant.now()))
        .as("held")
        .isEmpty();
    assertThat(
            claims.claimNext(
                "worker-b", settings.getLease(), Instant.now().plus(Duration.ofMinutes(5))))
        .as("and available again once the lease has run out, with nobody intervening")
        .isPresent();
    assertThat(jobRow("exp-leased").get("lease_owner")).isEqualTo("worker-b");
    assertThat(jobRow("exp-leased").get("attempt")).isEqualTo(2);
  }

  /**
   * The fence: a worker whose job was taken over cannot report anything.
   *
   * <p>It comes back with a complete file and is told no, and that is the right answer — the other worker has been
   * writing the same file, and whichever of them reports last would otherwise decide the outcome.
   */
  @Test
  void astalledWorkerCannotReportAnOutcomeAfterTheTakeover() {
    seed(PERIOD, 10);
    submit("exp-zombie");
    ExportJobId stalled = claimWithExpiredLease("worker-a");
    ExportJobId takenOver = claimAs("worker-b");
    assertThat(takenOver).isEqualTo(stalled);

    assertThat(runner.run(stalled, "worker-a"))
        .as("its whole run is discarded, not half-applied")
        .isEqualTo(ExportRunner.Outcome.SUPERSEDED);
    assertThat(jobRow("exp-zombie").get("status")).isEqualTo("RUNNING");
    assertThat(jobRow("exp-zombie").get("lease_owner")).isEqualTo("worker-b");

    assertThat(runner.run(takenOver, "worker-b")).isEqualTo(ExportRunner.Outcome.SUCCEEDED);
  }

  /** Refused rather than retried, which is why the fence is an explicit check and not the version. */
  @Test
  void thefenceIsARefusalWithACodeTheWorkerCanRecognise() {
    seed(PERIOD, 10);
    submit("exp-refused");
    claimWithExpiredLease("worker-a");
    claimAs("worker-b");
    assertThatThrownBy(
            () ->
                commandBus.send(
                    ReportExportOutcome.succeeded(
                        "exp-refused", "worker-a", new Artifact("/tmp/nope.csv", 1, 1))))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .contains(ReconciliationErrorCode.LEASE_LOST));
  }

  /**
   * The other race, and the reason the claim statement carries {@code version = version + 1}.
   *
   * <p>A cancellation that read the job a moment before the claim is refused on the version, retried by the
   * framework, and then records a request against the running job instead — the correct outcome, reached by the
   * ordinary machinery. Without the bump in the claim it would have committed as written, and a job would be
   * CANCELLED while a worker ran it to completion.
   */
  @Test
  void theclaimAdvancesTheVersionSoAStaleCancellationCannotWin() {
    seed(PERIOD, 10);
    submit("exp-raced");
    ExportJob readBeforeTheClaim = jobs.find(new ExportJobId("exp-raced")).orElseThrow();

    claimAs("worker-a");

    readBeforeTheClaim.requestCancel(Instant.now());
    assertThat(readBeforeTheClaim.status())
        .as("from its stale view, cancelling a queued job is immediate")
        .isEqualTo(ExportStatus.CANCELLED);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactions)
                    .executeWithoutResult(status -> jobs.save(readBeforeTheClaim)))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(jobRow("exp-raced").get("status")).isEqualTo("RUNNING");
  }

  /**
   * And the heartbeat tells a worker it has lost the job, so it can stop rather than finish for nothing.
   *
   * <p>Note the order these two assertions have to be in, and why. A heartbeat that <em>succeeds</em> extends the
   * lease — that is its whole job — so asserting the happy case first and then expecting a takeover would be asserting
   * against a lease this test had just pushed thirty seconds into the future. The takeover therefore uses a claim
   * clock in the future rather than an expired lease.
   */
  @Test
  void theheartbeatIsHowAWorkerLearnsItHasBeenSuperseded() {
    seed(PERIOD, 10);
    submit("exp-heartbeat");
    ExportJobId id = claimAs("worker-a");
    assertThat(claims.heartbeat(id, "worker-a", Instant.now().plus(Duration.ofSeconds(30))))
        .as("still ours")
        .isTrue();

    assertThat(
            claims.claimNext(
                "worker-b", settings.getLease(), Instant.now().plus(Duration.ofMinutes(5))))
        .as("taken over once the lease has run out")
        .isPresent();
    assertThat(claims.heartbeat(id, "worker-a", Instant.now().plus(Duration.ofSeconds(30))))
        .as("and now it is not ours, which is how the run learns to stop")
        .isFalse();
  }

  /** A queue with nothing claimable answers empty rather than waiting or failing. */
  @Test
  void anemptyQueueIsNotAnError() {
    assertThat(worker.runOne()).isEqualTo(Optional.empty());
  }
}
