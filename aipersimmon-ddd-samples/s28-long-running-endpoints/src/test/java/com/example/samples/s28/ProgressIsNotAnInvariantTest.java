package com.example.samples.s28;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.samples.s28.reconciliation.application.ExportJobQuery;
import com.example.samples.s28.reconciliation.application.ExportJobView;
import com.example.samples.s28.reconciliation.application.ExportRunner;
import com.example.samples.s28.reconciliation.application.RetryExport;
import com.example.samples.s28.reconciliation.domain.ExportJob;
import com.example.samples.s28.reconciliation.domain.ExportJobId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The scenario's answer to "should the job's state be an aggregate", measured on both halves.
 *
 * <p>Its <em>lifecycle</em> is an aggregate: {@code ExportJobTest} is a list of things somebody can be refused. Its
 * <em>progress</em> is not, and the tests here are the reason rather than a preference — a progress tick routed
 * through the aggregate advances the optimistic-lock version, which is where the one write that genuinely matters is
 * queuing.
 */
class ProgressIsNotAnInvariantTest extends ReconciliationTestBase {

  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactions;

  /** A thousand ticks leave the job exactly as they found it. */
  @Test
  void progressTicksNeverTouchTheJob() {
    ExportJobId id = submit("exp-ticks");
    long before = jobVersion("exp-ticks");
    for (int i = 1; i <= 1_000; i++) {
      progress.report(id, i, 1_000L);
    }
    assertThat(jobVersion("exp-ticks")).as("the aggregate was not written at all").isEqualTo(before);
    assertThat(progress.of(id)).get().extracting("rowsDone").isEqualTo(1_000L);
    assertThat(progressRows()).as("one row, overwritten").hasSize(1);
  }

  /**
   * And this is what it would cost if they did.
   *
   * <p>A version bump standing in for one progress tick is enough to make a cancellation that was already in flight
   * fail. Which is the whole argument in one measurement: a counter that changes thousands of times per run, sharing
   * a version with the decision a user is waiting on, means the decision loses — repeatedly, and for no reason
   * anybody could explain from the outside.
   */
  @Test
  void oneTickThroughTheAggregateIsEnoughToRefuseACancellation() {
    ExportJobId id = submit("exp-collide");
    ExportJob loadedForCancelling = jobs.find(id).orElseThrow();

    // Stand-in for a progress tick that went through the aggregate: it advances the version.
    jdbc.update("UPDATE s28_export_job SET version = version + 1 WHERE id = 'exp-collide'");

    loadedForCancelling.requestCancel(Instant.now());
    assertThatThrownBy(() -> inATransaction(() -> jobs.save(loadedForCancelling)))
        .isInstanceOf(OptimisticLockingFailureException.class)
        .hasMessageContaining("was modified concurrently");
  }

  /** With progress where it belongs, the same interleaving is a non-event. */
  @Test
  void athousandTicksDoNotDisturbACancellationInFlight() {
    ExportJobId id = submit("exp-quiet");
    ExportJob loadedForCancelling = jobs.find(id).orElseThrow();
    for (int i = 1; i <= 1_000; i++) {
      progress.report(id, i, 1_000L);
    }
    loadedForCancelling.requestCancel(Instant.now());
    inATransaction(() -> jobs.save(loadedForCancelling));
    assertThat(jobRow("exp-quiet").get("status")).isEqualTo("CANCELLED");
  }

  /**
   * A tick is visible to everybody else the moment it is written, while the writer's own transaction is still open.
   *
   * <p>Read on a second connection on purpose. Reading it back through the same transaction would prove nothing: a
   * transaction can always see its own uncommitted writes, which is exactly how the {@code SAME_TRANSACTION} mistake
   * passes its own author's test.
   */
  @Test
  void atickIsVisibleFromAnotherConnectionBeforeTheReaderFinishes() {
    ExportJobId id = submit("exp-visible");
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              progress.report(id, 4_100, 90_000L);
              assertThat(rowsDoneOnAnotherConnection("exp-visible"))
                  .as("committed on its own connection, so somebody polling can see it")
                  .isEqualTo(4_100L);
            });
  }

  /** Over a real run, the reading ends up where the artifact says it should. */
  @Test
  void arunPublishesItsProgressAndFinishesOnTheRowCount() {
    seed(PERIOD, 2_500);
    settings.setProgressInterval(500);
    try {
      submit("exp-run");
      ExportJobId claimed = claimAs("worker-a");
      assertThat(runner.run(claimed, "worker-a")).isEqualTo(ExportRunner.Outcome.SUCCEEDED);
      assertThat(rowsDoneOnAnotherConnection("exp-run")).isEqualTo(2_500L);
      assertThat(jobRow("exp-run").get("artifact_rows")).isEqualTo(2_500L);
    } finally {
      settings.setProgressInterval(1_000);
    }
  }

  /**
   * The view stops showing progress once the job is finished, and starts again from nothing after a retry.
   *
   * <p>Both halves matter to whoever is watching: a SUCCEEDED job with a progress reading beside it invites the
   * question of which number is right, and a re-queued job still reading "41,000 of 900,000" says work is happening
   * when none is.
   */
  @Test
  void theviewDropsProgressWhenFinishedAndForgetsItOnRetry() {
    seed(PERIOD, 100);
    submit("exp-view");
    ExportJobId claimed = claimAs("worker-a");
    runner.run(claimed, "worker-a");

    ExportJobView finished = queryBus.ask(new ExportJobQuery("exp-view"));
    assertThat(finished.progress()).as("terminal: the artifact is the authority now").isNull();
    assertThat(finished.artifactRows()).isEqualTo(100L);

    jdbc.update(
        "UPDATE s28_export_job SET status = 'FAILED', failure = 'pretend' WHERE id = 'exp-view'");
    progress.report(new ExportJobId("exp-view"), 41, 100L);
    commandBus.send(new RetryExport("exp-view"));
    assertThat(progress.of(new ExportJobId("exp-view"))).isEmpty();
    assertThat(queryBus.ask(new ExportJobQuery("exp-view")).progress()).isNull();
  }

  private void inATransaction(Runnable work) {
    new TransactionTemplate(transactions).executeWithoutResult(status -> work.run());
  }

  private long rowsDoneOnAnotherConnection(String jobId) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(true);
      try (PreparedStatement query =
          connection.prepareStatement("SELECT rows_done FROM s28_export_progress WHERE job_id = ?")) {
        query.setString(1, jobId);
        try (ResultSet rows = query.executeQuery()) {
          return rows.next() ? rows.getLong(1) : -1;
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
