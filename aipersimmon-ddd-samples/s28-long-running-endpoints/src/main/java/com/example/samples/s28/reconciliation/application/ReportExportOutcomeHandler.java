package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s28.reconciliation.domain.ExportJob;
import com.example.samples.s28.reconciliation.domain.ExportJobs;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Write the ending down, if the reporter is still entitled to.
 *
 * <p>The entitlement check lives in the aggregate, not here, which matters more than it looks: this handler has
 * no branch for "not held any more", so there is no way to write one that forgets. The refusal surfaces as
 * {@code LEASE_LOST} and the worker treats it as an ordinary lost race.
 */
@Component
class ReportExportOutcomeHandler implements CommandHandler<ReportExportOutcome, Void> {

  private final ExportJobs jobs;
  private final Exports exports;
  private final Clock clock;

  ReportExportOutcomeHandler(ExportJobs jobs, Exports exports, Clock clock) {
    this.jobs = jobs;
    this.exports = exports;
    this.clock = clock;
  }

  /**
   * No file is deleted here, deliberately. The draft belongs to the run that was writing it and is aborted by
   * that run; a handler that tidied up on this side would be deleting a file it cannot prove is the reporter's
   * — and the reporter may be the worker whose outcome is about to be refused.
   */
  @Override
  public Void handle(ReportExportOutcome command, CommandContext context) {
    ExportJob job = exports.require(command.exportId());
    var now = clock.instant();
    switch (command.ending()) {
      case SUCCEEDED -> job.succeeded(command.owner(), command.artifact(), now);
      case FAILED -> job.failed(command.owner(), command.reason(), now);
      case STOPPED -> job.cancelled(command.owner(), now);
    }
    jobs.save(job);
    return null;
  }
}
