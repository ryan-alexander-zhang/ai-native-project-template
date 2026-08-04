package com.example.samples.s28.reconciliation.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s28.reconciliation.domain.ImportBatch;
import com.example.samples.s28.reconciliation.domain.ImportBatchId;
import com.example.samples.s28.reconciliation.domain.ImportBatches;
import com.example.samples.s28.reconciliation.domain.ImportStatus;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** The batch's write path. */
@Repository
class MyBatisImportBatches extends MybatisPlusAggregateRepository<ImportBatch, ImportBatchRow>
    implements ImportBatches {

  private final ImportBatchMapper mapper;

  MyBatisImportBatches(ImportBatchMapper mapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
    this.mapper = mapper;
  }

  @Override
  public void save(ImportBatch batch) {
    saveAggregate(batch);
  }

  @Override
  public Optional<ImportBatch> find(ImportBatchId id) {
    ImportBatchRow row = mapper.selectById(id.value());
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        ImportBatch.reconstitute(
            id,
            row.getDeclaredChunks(),
            ImportStatus.valueOf(row.getStatus()),
            row.getAcceptedRows(),
            row.getFailure(),
            row.getOpenedAt(),
            row.getCompletedAt(),
            row.getVersion()));
  }

  @Override
  protected ImportBatchRow toRow(ImportBatch batch) {
    ImportBatchRow row = new ImportBatchRow();
    row.setId(batch.id().value());
    row.setDeclaredChunks(batch.declaredChunks());
    row.setStatus(batch.status().name());
    row.setAcceptedRows(batch.acceptedRows());
    row.setFailure(batch.failure().orElse(null));
    row.setOpenedAt(batch.openedAt());
    row.setCompletedAt(batch.completedAt().orElse(null));
    return row;
  }
}
