package com.example.samples.s28.reconciliation.application;

import com.example.samples.s28.reconciliation.domain.Artifact;
import com.example.samples.s28.reconciliation.domain.ExportJobId;
import java.io.InputStream;

/**
 * Where the bytes go, and where they come from when somebody downloads them.
 *
 * <p>Two things about this port carry most of the scenario's weight.
 *
 * <p><strong>The artifact is written before the job says it succeeded, and it is not visible until then.</strong>
 * A draft is written under a temporary name and only renamed into place on {@link Draft#commit}. Without that,
 * a job that dies three quarters of the way through leaves a plausible-looking file that a client, or a retry,
 * or an operator, may well pick up — a truncated reconciliation file is worse than a missing one, because
 * nothing about it looks wrong.
 *
 * <p><strong>The download reads from here, never from the database.</strong> Streaming query results straight
 * into an HTTP response puts the client's network speed in charge of how long a database transaction stays open:
 * a caller on a slow link, or one that stops reading halfway, holds a connection and a snapshot for as long as
 * it likes. Once the export is a file, the download is a file read — cancellable, resumable, and free of the
 * database entirely.
 */
public interface ArtifactStore {

  /** Start writing a job's file. */
  Draft begin(ExportJobId id);

  /** Read a finished artifact. The caller closes it. */
  InputStream open(Artifact artifact);

  /** Remove an artifact, for retention or for a retry that supersedes it. */
  void discard(Artifact artifact);

  /** A file being written, which becomes an {@link Artifact} only if it is finished. */
  interface Draft extends AutoCloseable {

    void writeLine(String line);

    /**
     * Publish the draft under its final name.
     *
     * @param rowCount how many source rows went in, so the artifact can state it
     */
    Artifact commit(long rowCount);

    /**
     * Throw the draft away. Idempotent, and safe to call after {@link #commit} — which is what makes
     * {@code try (Draft d = ...) }{@code { ... }} with an abort in the finally block a correct pattern rather
     * than a race.
     */
    void abort();

    /** Aborts unless the draft was committed. */
    @Override
    void close();
  }
}
