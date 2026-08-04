package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s28.reconciliation.domain.ExportJob;
import com.example.samples.s28.reconciliation.domain.ExportJobs;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** Record the request; the aggregate decides whether that means "cancelled" or "please stop". */
@Component
class CancelExportHandler implements CommandHandler<CancelExport, Boolean> {

  private final ExportJobs jobs;
  private final Exports exports;
  private final Clock clock;

  CancelExportHandler(ExportJobs jobs, Exports exports, Clock clock) {
    this.jobs = jobs;
    this.exports = exports;
    this.clock = clock;
  }

  @Override
  public Boolean handle(CancelExport command, CommandContext context) {
    ExportJob job = exports.require(command.exportId());
    if (!job.requestCancel(clock.instant())) {
      return false;
    }
    jobs.save(job);
    return true;
  }
}
