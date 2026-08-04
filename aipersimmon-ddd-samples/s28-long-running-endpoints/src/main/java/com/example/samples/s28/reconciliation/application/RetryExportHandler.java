package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s28.reconciliation.domain.ExportJob;
import com.example.samples.s28.reconciliation.domain.ExportJobs;
import org.springframework.stereotype.Component;

/**
 * Re-queue it, and clean up after the attempt that failed.
 *
 * <p>{@code forget} before {@code save}, and the artifact discarded, because a re-queued job whose progress row
 * still reads "41,000 of 900,000" tells whoever is watching that work is happening when nothing is.
 */
@Component
class RetryExportHandler implements CommandHandler<RetryExport, Void> {

  private final ExportJobs jobs;
  private final Exports exports;
  private final ProgressBoard progress;
  private final ArtifactStore artifacts;

  RetryExportHandler(
      ExportJobs jobs, Exports exports, ProgressBoard progress, ArtifactStore artifacts) {
    this.jobs = jobs;
    this.exports = exports;
    this.progress = progress;
    this.artifacts = artifacts;
  }

  @Override
  public Void handle(RetryExport command, CommandContext context) {
    ExportJob job = exports.require(command.exportId());
    job.artifact().ifPresent(artifacts::discard);
    job.retry();
    jobs.save(job);
    progress.forget(job.id());
    return null;
  }
}
