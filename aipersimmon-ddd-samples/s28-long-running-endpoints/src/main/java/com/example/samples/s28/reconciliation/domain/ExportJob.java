package com.example.samples.s28.reconciliation.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.time.Instant;
import java.util.Optional;

/**
 * A requested export, and the scenario's answer to "should a job's state be an aggregate".
 *
 * <p><strong>Yes for this, no for progress.</strong> Everything on this class is a decision somebody can be
 * refused on:
 *
 * <ul>
 *   <li>it cannot succeed twice, or succeed without an artifact;
 *   <li>it cannot be retried unless it failed;
 *   <li>it cannot be finished by a worker whose claim was taken over;
 *   <li>its request was fixed when it was submitted and cannot be changed underneath a running job.
 * </ul>
 *
 * <p>How far along it is, by contrast, decides nothing. No rule reads it, no refusal depends on it, a lost
 * tick costs nobody anything — and it changes thousands of times per run. It lives in its own table, outside
 * this class, and the reason is not tidiness: routing it through here would put every tick behind the
 * optimistic-lock version, where the one write that genuinely matters — the cancellation — is waiting.
 * {@code ProgressIsNotAnInvariantTest} measures that collision.
 *
 * <h2>Two races, two different guards</h2>
 *
 * <p>Long-running work is claimed rather than assigned, so two things can go wrong that a synchronous
 * handler never faces, and they need different answers:
 *
 * <ol>
 *   <li><strong>A stalled worker coming back to life.</strong> Its lease lapsed, somebody else took the job
 *       over, and it is about to report an outcome for work that has been superseded. Guarded by
 *       {@link #requireHeldBy} — an explicit check of the lease owner, raising {@link
 *       ReconciliationErrorCode#LEASE_LOST}. Deliberately <em>not</em> left to the optimistic lock: a
 *       version clash is retried by the framework's retry interceptor, and a retry here would re-read the
 *       job and cheerfully overwrite the new owner's work. A fence has to refuse, not retry.
 *   <li><strong>A request racing the claim.</strong> The claim is raw SQL (see {@code ExportClaims}) because
 *       a version-checked write is the wrong tool for a race that N workers are meant to enter and one is
 *       meant to win. That SQL therefore has to advance {@code version} itself, or a cancellation loaded a
 *       moment earlier would still commit and mark a job CANCELLED while a worker ran it to completion.
 *       That one is guarded by the optimistic lock, and only because the claim bumps the version.
 * </ol>
 *
 * <h2>Cancellation is a request, not a state</h2>
 *
 * <p>{@link #requestCancel} on a queued job cancels it outright — nothing is running, nothing was written.
 * On a <em>running</em> one it records a flag and returns; the worker acknowledges it at a chunk boundary
 * and calls {@link #cancelled}. Nothing here can put a running job into CANCELLED from outside, and that is
 * the point: a cancellation imposed from outside produces CANCELLED jobs whose artifact exists on disk, and
 * whoever reads the status will never look for the file.
 *
 * <p>The corollary is that {@link #succeeded} is allowed <em>after</em> a cancellation was requested, and
 * the sample would rather say so than hide it. The work finished; the file is written; a request that
 * arrived while the last row was being flushed cannot un-write it. The client learns the truth by polling,
 * which is exactly what it was going to do anyway.
 */
@AggregateRoot
public final class ExportJob extends AbstractAggregateRoot<ExportJobId> {

  private final ExportJobId id;
  private final ExportSpec spec;
  private final Instant submittedAt;

  private ExportStatus status;
  private int attempt;
  private String leaseOwner;
  private Instant leaseUntil;
  private boolean cancelRequested;
  private Artifact artifact;
  private String failure;
  private Instant startedAt;
  private Instant finishedAt;

  private ExportJob(
      ExportJobId id,
      ExportSpec spec,
      ExportStatus status,
      int attempt,
      String leaseOwner,
      Instant leaseUntil,
      boolean cancelRequested,
      Artifact artifact,
      String failure,
      Instant submittedAt,
      Instant startedAt,
      Instant finishedAt) {
    this.id = id;
    this.spec = spec;
    this.status = status;
    this.attempt = attempt;
    this.leaseOwner = leaseOwner;
    this.leaseUntil = leaseUntil;
    this.cancelRequested = cancelRequested;
    this.artifact = artifact;
    this.failure = failure;
    this.submittedAt = submittedAt;
    this.startedAt = startedAt;
    this.finishedAt = finishedAt;
  }

  /** A new request: queued, never touched, no attempts yet. */
  public static ExportJob submit(ExportJobId id, ExportSpec spec, Instant at) {
    if (at == null) {
      throw new IllegalArgumentException("submission time required");
    }
    return new ExportJob(
        requireId(id),
        requireSpec(spec),
        ExportStatus.QUEUED,
        0,
        null,
        null,
        false,
        null,
        null,
        at,
        null,
        null);
  }

  public static ExportJob reconstitute(
      ExportJobId id,
      ExportSpec spec,
      ExportStatus status,
      int attempt,
      String leaseOwner,
      Instant leaseUntil,
      boolean cancelRequested,
      Artifact artifact,
      String failure,
      Instant submittedAt,
      Instant startedAt,
      Instant finishedAt,
      long version) {
    ExportJob job =
        new ExportJob(
            id,
            spec,
            status,
            attempt,
            leaseOwner,
            leaseUntil,
            cancelRequested,
            artifact,
            failure,
            submittedAt,
            startedAt,
            finishedAt);
    job.restoreVersion(version);
    return job;
  }

  /**
   * Ask for this to stop.
   *
   * @return false when there is nothing to do — the job already finished, or the request was already
   *     recorded — so a client retrying its cancellation is not told it failed
   */
  public boolean requestCancel(Instant at) {
    if (status.isTerminal()) {
      return false;
    }
    if (status == ExportStatus.QUEUED) {
      // Nothing has run and nothing was written, so there is nothing to acknowledge.
      this.status = ExportStatus.CANCELLED;
      this.finishedAt = at;
      this.cancelRequested = true;
      return true;
    }
    if (cancelRequested) {
      return false;
    }
    this.cancelRequested = true;
    return true;
  }

  /** The worker finished the file. */
  public void succeeded(String owner, Artifact producedArtifact, Instant at) {
    requireHeldBy(owner);
    if (producedArtifact == null) {
      throw new IllegalArgumentException(
          "a succeeded export must name its artifact; there is no such thing as a successful export of"
              + " nothing");
    }
    this.artifact = producedArtifact;
    this.status = ExportStatus.SUCCEEDED;
    this.failure = null;
    this.finishedAt = at;
    releaseLease();
  }

  /**
   * The worker could not finish.
   *
   * <p>The reason is kept, and kept on the job rather than only in a log line, because the caller polling
   * this resource is the one who needs it. A job that reports FAILED with nothing else to say sends its
   * client to ask an operator, which is the failure mode this column exists to prevent.
   */
  public void failed(String owner, String reason, Instant at) {
    requireHeldBy(owner);
    this.status = ExportStatus.FAILED;
    this.failure = truncate(reason);
    this.artifact = null;
    this.finishedAt = at;
    releaseLease();
  }

  /** The worker noticed the cancellation request and stopped. */
  public void cancelled(String owner, Instant at) {
    requireHeldBy(owner);
    if (!cancelRequested) {
      throw new IllegalStateException(
          "export " + id + " was not asked to stop, so a worker has no business cancelling it");
    }
    this.status = ExportStatus.CANCELLED;
    this.artifact = null;
    this.finishedAt = at;
    releaseLease();
  }

  /**
   * Put a failed job back in the queue.
   *
   * <p>{@code attempt} is not reset. It is the count of times a worker has picked this job up, and a job on
   * its fifth attempt is a different operational object from a fresh one — that is precisely what somebody
   * looking at the queue needs to see.
   *
   * @throws DomainException if the job did not fail
   */
  public void retry() {
    if (status != ExportStatus.FAILED) {
      throw new DomainException(
          ReconciliationErrorCode.EXPORT_NOT_RETRYABLE,
          "export " + id + " is " + status + "; only a failed export can be retried");
    }
    this.status = ExportStatus.QUEUED;
    this.cancelRequested = false;
    this.finishedAt = null;
    releaseLease();
  }

  /** Whether {@code owner} still holds the claim, so a worker can stop early instead of being refused. */
  public boolean isHeldBy(String owner) {
    return status == ExportStatus.RUNNING && owner != null && owner.equals(leaseOwner);
  }

  private void requireHeldBy(String owner) {
    if (!isHeldBy(owner)) {
      throw new DomainException(
          ReconciliationErrorCode.LEASE_LOST,
          "worker "
              + owner
              + " no longer holds export "
              + id
              + " (status "
              + status
              + ", held by "
              + leaseOwner
              + "); its outcome is discarded because another worker has taken the job over");
    }
  }

  private void releaseLease() {
    this.leaseOwner = null;
    this.leaseUntil = null;
  }

  public ExportJobId id() {
    return id;
  }

  public ExportSpec spec() {
    return spec;
  }

  public ExportStatus status() {
    return status;
  }

  public int attempt() {
    return attempt;
  }

  public boolean cancelRequested() {
    return cancelRequested;
  }

  public Optional<String> leaseOwner() {
    return Optional.ofNullable(leaseOwner);
  }

  public Optional<Instant> leaseUntil() {
    return Optional.ofNullable(leaseUntil);
  }

  public Optional<Artifact> artifact() {
    return Optional.ofNullable(artifact);
  }

  public Optional<String> failure() {
    return Optional.ofNullable(failure);
  }

  public Instant submittedAt() {
    return submittedAt;
  }

  public Optional<Instant> startedAt() {
    return Optional.ofNullable(startedAt);
  }

  public Optional<Instant> finishedAt() {
    return Optional.ofNullable(finishedAt);
  }

  private static ExportJobId requireId(ExportJobId id) {
    if (id == null) {
      throw new IllegalArgumentException("export job id required");
    }
    return id;
  }

  private static ExportSpec requireSpec(ExportSpec spec) {
    if (spec == null) {
      throw new IllegalArgumentException("export spec required");
    }
    return spec;
  }

  /** The column is 1024; a stack-trace-shaped reason must not be the thing that fails the save. */
  private static String truncate(String reason) {
    if (reason == null || reason.isBlank()) {
      return "no reason recorded";
    }
    return reason.length() <= 1024 ? reason : reason.substring(0, 1021) + "...";
  }
}
