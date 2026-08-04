package com.example.samples.s28.reconciliation.domain;

import java.util.Optional;

/** The export job's repository. */
public interface ExportJobs {

  Optional<ExportJob> find(ExportJobId id);

  void save(ExportJob job);

  /**
   * Has this job been asked to stop? One column, one round trip, no aggregate.
   *
   * <p>It sits on the repository rather than being answered by {@code find(id).cancelRequested()} because of
   * how often the worker asks: once per progress interval, for the whole length of the export. Loading the
   * whole aggregate to read one boolean would be a reconstitute per thousand rows, and — worse — it would
   * hand the worker a second, staler copy of the job whose version it might be tempted to write back.
   */
  boolean isCancelRequested(ExportJobId id);
}
