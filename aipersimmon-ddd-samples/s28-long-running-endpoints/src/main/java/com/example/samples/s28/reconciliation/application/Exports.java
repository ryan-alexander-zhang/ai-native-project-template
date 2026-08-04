package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.example.samples.s28.reconciliation.domain.ExportJob;
import com.example.samples.s28.reconciliation.domain.ExportJobId;
import com.example.samples.s28.reconciliation.domain.ExportJobs;
import com.example.samples.s28.reconciliation.domain.ReconciliationErrorCode;
import org.springframework.stereotype.Component;

/**
 * "Load it or 404", in one place.
 *
 * <p>Six handlers need the same three lines, and the reason to factor them is not brevity: a job that cannot
 * be found has to be a 404 <em>every</em> time, and one handler that forgets and returns an empty view instead
 * is a client silently polling a job that does not exist.
 */
@Component
class Exports {

  private final ExportJobs jobs;

  Exports(ExportJobs jobs) {
    this.jobs = jobs;
  }

  ExportJob require(String exportId) {
    ExportJobId id = new ExportJobId(exportId);
    return jobs.find(id)
        .orElseThrow(
            () ->
                new EntityNotFoundException(
                    ReconciliationErrorCode.EXPORT_NOT_FOUND, "no export " + exportId));
  }
}
