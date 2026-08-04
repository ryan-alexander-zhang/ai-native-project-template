package com.example.samples.s28.reconciliation.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/** This context's refusals. */
public enum ReconciliationErrorCode implements ErrorCode {
  EXPORT_NOT_FOUND("reconciliation.export-not-found", ErrorCategory.NOT_FOUND),
  IMPORT_NOT_FOUND("reconciliation.import-not-found", ErrorCategory.NOT_FOUND),

  /**
   * The job id is taken by a request for something else — a different period, a different chunk count. The
   * asynchronous contract's version of the idempotency store's {@code Mismatch}: replaying would hand back an
   * outcome for something the caller did not ask about, and executing would overwrite somebody's job.
   */
  REQUEST_MISMATCH("reconciliation.request-mismatch", ErrorCategory.CONFLICT),

  /** The job exists but has produced nothing to download yet. Not a 404 — the job is real. */
  ARTIFACT_NOT_READY("reconciliation.artifact-not-ready", ErrorCategory.CONFLICT),

  /** Only a failed job can be retried; a succeeded one has an artifact and a cancelled one was refused. */
  EXPORT_NOT_RETRYABLE("reconciliation.export-not-retryable", ErrorCategory.CONFLICT),

  /**
   * The worker reporting an outcome no longer holds the claim: its lease lapsed and somebody else took the
   * job over. The fence that stops two workers from both finishing one job — see {@code ExportJob}.
   */
  LEASE_LOST("reconciliation.lease-lost", ErrorCategory.CONFLICT),

  /** Completion asked for while chunks are missing. The message names which ones. */
  CHUNKS_MISSING("reconciliation.chunks-missing", ErrorCategory.CONFLICT),

  /** A chunk arriving for a batch that is already closed. */
  BATCH_CLOSED("reconciliation.batch-closed", ErrorCategory.CONFLICT),

  /** The chunk's bytes do not hash to what the client said they would. */
  CHUNK_CORRUPT("reconciliation.chunk-corrupt", ErrorCategory.VALIDATION);

  private final String code;
  private final ErrorCategory category;

  ReconciliationErrorCode(String code, ErrorCategory category) {
    this.code = code;
    this.category = category;
  }

  @Override
  public String code() {
    return code;
  }

  @Override
  public ErrorCategory category() {
    return category;
  }
}
