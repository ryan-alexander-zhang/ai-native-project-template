package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.cqrs.Command;
import com.example.samples.s28.reconciliation.domain.Artifact;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The worker saying how it went. One command for all three endings, because the worker has exactly one moment
 * at which it reports and the alternative is three near-identical handlers.
 *
 * <p>It is still a command and it still goes through the bus, which is what gives it a transaction, validation
 * and the conflict translation every other write gets. The temptation with worker-internal transitions is to
 * let them call the repository directly and skip the ceremony; the price of skipping it is that the one write
 * that has a real concurrency story — this one — is the only write with no machinery around it.
 *
 * <p>{@code owner} is not decoration. It is the fence: the aggregate refuses an outcome from a worker whose
 * claim was taken over, and the only way it can tell is by being told who is reporting.
 *
 * @param exportId which job
 * @param owner who is reporting; refused unless it still holds the claim
 * @param ending what happened
 * @param artifact the file, for {@link Ending#SUCCEEDED} only
 * @param reason the failure, for {@link Ending#FAILED} only
 */
public record ReportExportOutcome(
    @NotBlank String exportId,
    @NotBlank String owner,
    @NotNull Ending ending,
    Artifact artifact,
    String reason)
    implements Command<Void> {

  public enum Ending {
    SUCCEEDED,
    FAILED,
    /** The worker saw the cancellation request and stopped. */
    STOPPED
  }

  public static ReportExportOutcome succeeded(String exportId, String owner, Artifact artifact) {
    return new ReportExportOutcome(exportId, owner, Ending.SUCCEEDED, artifact, null);
  }

  public static ReportExportOutcome failed(String exportId, String owner, String reason) {
    return new ReportExportOutcome(exportId, owner, Ending.FAILED, null, reason);
  }

  public static ReportExportOutcome stopped(String exportId, String owner) {
    return new ReportExportOutcome(exportId, owner, Ending.STOPPED, null, null);
  }
}
