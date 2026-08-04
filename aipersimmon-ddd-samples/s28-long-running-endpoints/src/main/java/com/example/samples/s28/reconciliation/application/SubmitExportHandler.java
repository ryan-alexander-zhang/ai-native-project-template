package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s28.reconciliation.domain.ExportJob;
import com.example.samples.s28.reconciliation.domain.ExportJobId;
import com.example.samples.s28.reconciliation.domain.ExportJobs;
import com.example.samples.s28.reconciliation.domain.ExportSpec;
import com.example.samples.s28.reconciliation.domain.ReconciliationErrorCode;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Queue it, or recognise that it is already queued.
 *
 * <p>Three outcomes and no fourth, which is what makes the endpoint safe to retry:
 *
 * <ul>
 *   <li>the id is free — create the job, answer "created";
 *   <li>the id names a job for the same period — answer "already there", touching nothing. Note that this
 *       holds whatever state the job is in: a client retrying an accepted request while the export runs, or
 *       after it finished, gets pointed at the same resource rather than starting the work again;
 *   <li>the id names a job for a <em>different</em> period — refuse. Replaying would hand back an outcome for
 *       something the caller did not ask about, and executing would overwrite somebody's job.
 * </ul>
 *
 * <p>There is no check-then-insert window worth worrying about. Two concurrent first attempts both find
 * nothing and both insert, and the library's {@code saveAggregate} turns the loser's duplicate key into a
 * {@code DuplicateEntityException} — a 409 the client can retry into the "already there" branch.
 */
@Component
class SubmitExportHandler implements CommandHandler<SubmitExport, Boolean> {

  private final ExportJobs jobs;
  private final Clock clock;

  SubmitExportHandler(ExportJobs jobs, Clock clock) {
    this.jobs = jobs;
    this.clock = clock;
  }

  @Override
  public Boolean handle(SubmitExport command, CommandContext context) {
    ExportJobId id = new ExportJobId(command.exportId());
    ExportSpec requested = new ExportSpec(command.period(), ExportSpec.ExportFormat.CSV);
    Optional<ExportJob> existing = jobs.find(id);
    if (existing.isPresent()) {
      ExportSpec already = existing.get().spec();
      if (!already.equals(requested)) {
        throw new DomainException(
            ReconciliationErrorCode.REQUEST_MISMATCH,
            "export "
                + id
                + " already exists for period "
                + already.period()
                + ", so it cannot also be a request for "
                + requested.period());
      }
      return false;
    }
    jobs.save(ExportJob.submit(id, requested, clock.instant()));
    return true;
  }
}
