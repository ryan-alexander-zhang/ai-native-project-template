package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.example.samples.s28.reconciliation.domain.Artifact;
import com.example.samples.s28.reconciliation.domain.ExportJob;
import com.example.samples.s28.reconciliation.domain.ExportStatus;
import org.springframework.stereotype.Component;

/**
 * Two reads, joined here rather than in SQL.
 *
 * <p>The job and its progress live in different tables with different write patterns, and this is the one place
 * that puts them back together. A join would work and would also tie the read to the two tables staying in one
 * database, which is a promise this scenario has no reason to make about a progress counter.
 *
 * <p>Progress is dropped from the view once the job is finished. A SUCCEEDED job's authoritative row count is on
 * the artifact; showing a progress reading beside it invites the question of which one is right.
 */
@Component
class ExportJobQueryHandler implements QueryHandler<ExportJobQuery, ExportJobView> {

  private final Exports exports;
  private final ProgressBoard progress;

  ExportJobQueryHandler(Exports exports, ProgressBoard progress) {
    this.exports = exports;
    this.progress = progress;
  }

  @Override
  public ExportJobView handle(ExportJobQuery query) {
    ExportJob job = exports.require(query.exportId());
    ExportProgress reading =
        job.status().isTerminal() ? null : progress.of(job.id()).orElse(null);
    Artifact artifact = job.artifact().orElse(null);
    return new ExportJobView(
        job.id().value(),
        job.spec().period(),
        job.status(),
        job.attempt(),
        job.cancelRequested(),
        reading,
        artifact == null ? null : artifact.bytes(),
        artifact == null ? null : artifact.rowCount(),
        job.status() == ExportStatus.SUCCEEDED ? "/exports/" + job.id().value() + "/content" : null,
        job.failure().orElse(null),
        job.submittedAt(),
        job.finishedAt().orElse(null));
  }
}
