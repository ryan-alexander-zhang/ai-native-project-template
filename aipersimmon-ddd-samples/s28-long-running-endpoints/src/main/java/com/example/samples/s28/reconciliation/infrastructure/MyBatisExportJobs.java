package com.example.samples.s28.reconciliation.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s28.reconciliation.domain.Artifact;
import com.example.samples.s28.reconciliation.domain.ExportJob;
import com.example.samples.s28.reconciliation.domain.ExportJobId;
import com.example.samples.s28.reconciliation.domain.ExportJobs;
import com.example.samples.s28.reconciliation.domain.ExportSpec;
import com.example.samples.s28.reconciliation.domain.ExportStatus;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * The job's write path.
 *
 * <p>{@code toRow} maps every column the aggregate owns, including the ones it has emptied — a finished job has
 * no lease owner and a failed one has no artifact, and both of those are {@code null}s that have to reach the
 * database. That is exactly the case the library's {@code ClearedColumns} exists for: MyBatis-Plus would leave a
 * null field out of the {@code SET} clause, the update would report success, and the old lease owner would come
 * back on the next load. Nothing here has to do anything to get that right, which is the point of the base class
 * — but it is worth knowing which of these nulls depend on it.
 */
@Repository
class MyBatisExportJobs extends MybatisPlusAggregateRepository<ExportJob, ExportJobRow>
    implements ExportJobs {

  private final ExportJobMapper mapper;

  MyBatisExportJobs(ExportJobMapper mapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
    this.mapper = mapper;
  }

  @Override
  public void save(ExportJob job) {
    saveAggregate(job);
  }

  @Override
  public Optional<ExportJob> find(ExportJobId id) {
    ExportJobRow row = mapper.selectById(id.value());
    if (row == null) {
      return Optional.empty();
    }
    Artifact artifact =
        row.getArtifactPath() == null
            ? null
            : new Artifact(row.getArtifactPath(), row.getArtifactBytes(), row.getArtifactRows());
    return Optional.of(
        ExportJob.reconstitute(
            id,
            new ExportSpec(row.getPeriod(), ExportSpec.ExportFormat.valueOf(row.getFormat())),
            ExportStatus.valueOf(row.getStatus()),
            row.getAttempt(),
            row.getLeaseOwner(),
            row.getLeaseUntil(),
            Boolean.TRUE.equals(row.getCancelRequested()),
            artifact,
            row.getFailure(),
            row.getSubmittedAt(),
            row.getStartedAt(),
            row.getFinishedAt(),
            row.getVersion()));
  }

  @Override
  public boolean isCancelRequested(ExportJobId id) {
    return Boolean.TRUE.equals(mapper.readCancelRequested(id.value()));
  }

  @Override
  protected ExportJobRow toRow(ExportJob job) {
    ExportJobRow row = new ExportJobRow();
    row.setId(job.id().value());
    row.setPeriod(job.spec().period());
    row.setFormat(job.spec().format().name());
    row.setStatus(job.status().name());
    row.setAttempt(job.attempt());
    row.setLeaseOwner(job.leaseOwner().orElse(null));
    row.setLeaseUntil(job.leaseUntil().orElse(null));
    row.setCancelRequested(job.cancelRequested());
    row.setArtifactPath(job.artifact().map(Artifact::path).orElse(null));
    row.setArtifactBytes(job.artifact().map(Artifact::bytes).orElse(null));
    row.setArtifactRows(job.artifact().map(Artifact::rowCount).orElse(null));
    row.setFailure(job.failure().orElse(null));
    row.setSubmittedAt(job.submittedAt());
    row.setStartedAt(job.startedAt().orElse(null));
    row.setFinishedAt(job.finishedAt().orElse(null));
    return row;
  }
}
