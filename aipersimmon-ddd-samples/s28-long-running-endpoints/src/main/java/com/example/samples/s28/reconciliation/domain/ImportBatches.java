package com.example.samples.s28.reconciliation.domain;

import java.util.Optional;

/** The import batch's repository. */
public interface ImportBatches {

  Optional<ImportBatch> find(ImportBatchId id);

  void save(ImportBatch batch);
}
