package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.samples.s28.reconciliation.domain.Artifact;
import com.example.samples.s28.reconciliation.domain.ExportJob;
import com.example.samples.s28.reconciliation.domain.ExportStatus;
import com.example.samples.s28.reconciliation.domain.ReconciliationErrorCode;
import org.springframework.stereotype.Component;

/**
 * Serving the file, and the two refusals that have to be told apart.
 *
 * <p>A download asked for too early is <strong>not</strong> a 404. The job exists, the caller has the right id, and
 * the answer "no such thing" would send them to check their id instead of waiting. It is a conflict with the job's
 * current state, which is the same distinction S27 drew for a suppressed row: what is being asked about exists, the
 * question is just not answerable yet.
 *
 * <p>Not a query on the query bus, because the result is an open handle rather than a value. A read-side
 * interceptor that logged, cached or retried it would be holding an {@code InputStream} whose lifetime it does not
 * control.
 */
@Component
public class ExportDownloads {

  private final Exports exports;
  private final ArtifactStore artifacts;

  ExportDownloads(Exports exports, ArtifactStore artifacts) {
    this.exports = exports;
    this.artifacts = artifacts;
  }

  /**
   * Open a finished export's artifact.
   *
   * @throws com.aipersimmon.ddd.application.EntityNotFoundException if there is no such job
   * @throws DomainException if the job has not produced an artifact
   */
  public ExportDownload open(String exportId) {
    ExportJob job = exports.require(exportId);
    if (job.status() != ExportStatus.SUCCEEDED) {
      throw new DomainException(
          ReconciliationErrorCode.ARTIFACT_NOT_READY,
          "export " + exportId + " is " + job.status() + "; there is nothing to download yet");
    }
    Artifact artifact =
        job.artifact()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "export "
                            + exportId
                            + " succeeded without an artifact, which the aggregate refuses to allow"));
    return new ExportDownload(
        exportId + ".csv", artifact.bytes(), artifacts.open(artifact));
  }
}
