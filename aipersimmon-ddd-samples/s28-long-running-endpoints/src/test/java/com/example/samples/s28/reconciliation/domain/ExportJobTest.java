package com.example.samples.s28.reconciliation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.core.exception.DomainException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The job's state machine, in plain unit tests. The cheapest layer that can answer these, per S18.
 *
 * <p>Every one of these is a rule, and their existence is the answer to "should the job's state be an aggregate":
 * something has to refuse these, and a table with a status column refuses nothing.
 */
class ExportJobTest {

  private static final ExportJobId ID = new ExportJobId("exp-1");
  private static final ExportSpec SPEC = new ExportSpec("2026-06", ExportSpec.ExportFormat.CSV);
  private static final Instant NOW = Instant.parse("2026-07-01T10:00:00Z");
  private static final String OWNER = "worker-a";
  private static final Artifact FILE = new Artifact("/tmp/exp-1.csv", 4096, 900);

  private ExportJob queued() {
    return ExportJob.submit(ID, SPEC, NOW);
  }

  /** As claimed: the claim SQL sets these columns, so this is what the worker loads. */
  private ExportJob running(boolean cancelRequested) {
    return ExportJob.reconstitute(
        ID,
        SPEC,
        ExportStatus.RUNNING,
        1,
        OWNER,
        NOW.plusSeconds(30),
        cancelRequested,
        null,
        null,
        NOW,
        NOW,
        null,
        2);
  }

  @Test
  void asubmittedJobIsQueuedAndHasNotBeenTriedYet() {
    ExportJob job = queued();
    assertThat(job.status()).isEqualTo(ExportStatus.QUEUED);
    assertThat(job.attempt()).isZero();
    assertThat(job.artifact()).isEmpty();
    assertThat(job.leaseOwner()).isEmpty();
  }

  @Test
  void cancellingaQueuedJobIsImmediateBecauseNothingHasRun() {
    ExportJob job = queued();
    assertThat(job.requestCancel(NOW)).isTrue();
    assertThat(job.status()).isEqualTo(ExportStatus.CANCELLED);
    assertThat(job.finishedAt()).contains(NOW);
  }

  /**
   * The distinction the whole cancellation design rests on: a running job is <em>asked</em>, not moved.
   *
   * <p>Moving it from outside would produce CANCELLED jobs whose file exists, and nobody reading the status would
   * think to look for one.
   */
  @Test
  void cancellingaRunningJobOnlyRecordsTheRequest() {
    ExportJob job = running(false);
    assertThat(job.requestCancel(NOW)).isTrue();
    assertThat(job.status()).isEqualTo(ExportStatus.RUNNING);
    assertThat(job.cancelRequested()).isTrue();
    assertThat(job.finishedAt()).isEmpty();
  }

  @Test
  void asecondCancellationRequestChangesNothingAndSaysSo() {
    ExportJob job = running(true);
    assertThat(job.requestCancel(NOW)).isFalse();
  }

  @Test
  void cancellingaFinishedJobChangesNothingAndSaysSo() {
    ExportJob job = running(false);
    job.succeeded(OWNER, FILE, NOW);
    assertThat(job.requestCancel(NOW)).isFalse();
    assertThat(job.status()).isEqualTo(ExportStatus.SUCCEEDED);
  }

  /**
   * The uncomfortable one, asserted rather than avoided.
   *
   * <p>The work finished; the file is written; a cancellation that arrived while the last row was being flushed
   * cannot un-write it. Refusing the success here would leave a CANCELLED job with an artifact on disk, which is
   * strictly worse than a client learning by polling that it was too late.
   */
  @Test
  void ajobThatFinishedBeforeNoticingTheCancellationStillSucceeds() {
    ExportJob job = running(true);
    job.succeeded(OWNER, FILE, NOW);
    assertThat(job.status()).isEqualTo(ExportStatus.SUCCEEDED);
    assertThat(job.artifact()).contains(FILE);
  }

  @Test
  void aworkerMayOnlyReportCancelledForAJobThatWasAsked() {
    ExportJob job = running(false);
    assertThatThrownBy(() -> job.cancelled(OWNER, NOW))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("was not asked to stop");
  }

  /** The fence, stated as a rule rather than left to the version arithmetic. */
  @Test
  void aworkerThatLostItsClaimCannotReportAnything() {
    ExportJob job = running(false);
    assertThatThrownBy(() -> job.succeeded("worker-b", FILE, NOW))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .contains(ReconciliationErrorCode.LEASE_LOST));
  }

  @Test
  void aqueuedJobHasNoOwnerSoNobodyCanFinishIt() {
    ExportJob job = queued();
    assertThatThrownBy(() -> job.succeeded(OWNER, FILE, NOW))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("no longer holds");
  }

  @Test
  void therecannotBeASuccessfulExportOfNothing() {
    ExportJob job = running(false);
    assertThatThrownBy(() -> job.succeeded(OWNER, null, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must name its artifact");
  }

  @Test
  void afailureKeepsItsReasonAndDropsTheLease() {
    ExportJob job = running(false);
    job.failed(OWNER, "the source table went away", NOW);
    assertThat(job.status()).isEqualTo(ExportStatus.FAILED);
    assertThat(job.failure()).contains("the source table went away");
    assertThat(job.leaseOwner()).isEmpty();
    assertThat(job.artifact()).isEmpty();
  }

  @Test
  void afailureWithNothingToSayStillSaysSomething() {
    ExportJob job = running(false);
    job.failed(OWNER, null, NOW);
    assertThat(job.failure()).contains("no reason recorded");
  }

  @Test
  void retryRequeuesAndKeepsTheAttemptCount() {
    ExportJob job = running(false);
    job.failed(OWNER, "boom", NOW);
    job.retry();
    assertThat(job.status()).isEqualTo(ExportStatus.QUEUED);
    assertThat(job.attempt()).isEqualTo(1);
    assertThat(job.finishedAt()).isEmpty();
    assertThat(job.cancelRequested()).isFalse();
  }

  @Test
  void onlyaFailedJobCanBeRetried() {
    ExportJob job = running(false);
    job.succeeded(OWNER, FILE, NOW);
    assertThatThrownBy(job::retry)
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .contains(ReconciliationErrorCode.EXPORT_NOT_RETRYABLE));
  }

  /**
   * There is nowhere to put progress, and that is the design rather than an omission.
   *
   * <p>Asserted structurally so that adding one has to come past this test and read why not.
   */
  @Test
  void thejobHasNoIdeaHowFarAlongItIs() {
    assertThat(ExportJob.class.getDeclaredFields())
        .extracting(java.lang.reflect.Field::getName)
        .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("progress"))
        .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("rowsdone"));
    assertThat(ExportJob.class.getDeclaredMethods())
        .extracting(java.lang.reflect.Method::getName)
        .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("progress"));
  }
}
