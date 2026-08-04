package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.example.samples.s28.reconciliation.domain.Artifact;
import com.example.samples.s28.reconciliation.domain.ExportJob;
import com.example.samples.s28.reconciliation.domain.ExportJobId;
import com.example.samples.s28.reconciliation.domain.ExportJobs;
import com.example.samples.s28.reconciliation.domain.ReconciliationErrorCode;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One claimed job, run to an ending. The positive form of everything this scenario says not to do.
 *
 * <p><strong>What runs where.</strong> The export itself is <em>not</em> in a business transaction. It reads
 * inside a read-only one (see {@link ExportSettings.ReadMode}), publishes progress on a connection of its own,
 * and writes the outcome afterwards through the command bus, which supplies a short transaction for that one
 * write. Wrapping the whole thing in a transaction is the obvious first version and it is wrong in three ways
 * at once: the connection is held for the duration, nothing is visible until the end, and a failure at 95%
 * discards a file that was already written to disk anyway.
 *
 * <p><strong>Why the outcome is a command.</strong> The run itself is plumbing, but the ending is a decision
 * with a rule attached — it can be refused. Sending it through the bus gets it the same transaction,
 * validation, retry translation and audit surface as any other write. The alternative, calling the repository
 * from here, would leave the one write with a genuine concurrency story as the only one with no machinery
 * around it.
 *
 * <p><strong>Where cancellation is noticed.</strong> At every progress interval, by asking for one boolean.
 * Not more often, because a cancellation is not urgent enough to pay a query per row; not less often, because
 * the interval is also the granularity of "how long after cancelling does the work actually stop", and that is
 * a number somebody will ask about.
 *
 * <p><strong>What happens to a partial file.</strong> Nothing keeps it. The draft is aborted on every path out
 * of here that is not a success, and it was never visible under its final name to begin with, so there is no
 * window in which a truncated reconciliation file exists where a finished one belongs.
 */
@Component
public class ExportRunner {

  private static final Logger log = LoggerFactory.getLogger(ExportRunner.class);

  private final ExportJobs jobs;
  private final ExportSource source;
  private final ArtifactStore artifacts;
  private final ProgressBoard progress;
  private final ExportClaims claims;
  private final CommandBus commands;
  private final ExportSettings settings;
  private final TransactionTemplate readTransaction;
  private final Clock clock;

  ExportRunner(
      ExportJobs jobs,
      ExportSource source,
      ArtifactStore artifacts,
      ProgressBoard progress,
      ExportClaims claims,
      CommandBus commands,
      ExportSettings settings,
      PlatformTransactionManager transactions,
      Clock clock) {
    this.jobs = jobs;
    this.source = source;
    this.artifacts = artifacts;
    this.progress = progress;
    this.claims = claims;
    this.commands = commands;
    this.settings = settings;
    this.readTransaction = newReadTransaction(transactions, settings);
    this.clock = clock;
  }

  /**
   * The transaction the source rows are read in, and one detail that is easy to get backwards.
   *
   * <p>It <em>wants</em> to be read-only: the export writes nothing to the database, and saying so lets the
   * driver and the server behave accordingly. But a read-only transaction on PostgreSQL rejects every write on
   * its connection, so it can only be marked read-only if the progress ticks are going somewhere else — which
   * they are, by default, and which is one more reason for them to. Under
   * {@link ExportSettings.ProgressTransaction#SAME_TRANSACTION} the ticks join this transaction, so the flag
   * has to come off, and the mode's real cost then becomes visible instead of being masked by a hard error.
   */
  private static TransactionTemplate newReadTransaction(
      PlatformTransactionManager transactions, ExportSettings settings) {
    TransactionTemplate template = new TransactionTemplate(transactions);
    template.setReadOnly(
        settings.getProgressTransaction() == ExportSettings.ProgressTransaction.OWN_TRANSACTION);
    return template;
  }

  /** The outcome, so a caller driving one run in a test can assert on it without polling. */
  public enum Outcome {
    SUCCEEDED,
    FAILED,
    STOPPED,
    /** The claim was lost mid-run; another worker owns the job now and this run's output is discarded. */
    SUPERSEDED
  }

  /**
   * Run the job {@code owner} has already claimed.
   *
   * @param id the claimed job
   * @param owner the claim's owner, carried all the way to the outcome so the fence has something to check
   */
  public Outcome run(ExportJobId id, String owner) {
    ExportJob job =
        jobs.find(id)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "export " + id + " was claimed and then vanished, which cannot happen"));
    String period = job.spec().period();
    AtomicLong written = new AtomicLong();
    boolean stopped;
    Artifact artifact = null;
    try {
      long total = source.countPeriod(period);
      progress.report(id, 0, total);
      try (ArtifactStore.Draft draft = artifacts.begin(id)) {
        draft.writeLine("id,order_ref,amount_cents,note");
        stopped = writeRows(id, owner, period, total, draft, written);
        if (!stopped) {
          artifact = draft.commit(written.get());
        }
      }
    } catch (RuntimeException e) {
      log.warn("export {} failed after {} rows", id, written.get(), e);
      return report(ReportExportOutcome.failed(id.value(), owner, describe(e)), Outcome.FAILED);
    }
    return stopped
        ? report(ReportExportOutcome.stopped(id.value(), owner), Outcome.STOPPED)
        : report(ReportExportOutcome.succeeded(id.value(), owner, artifact), Outcome.SUCCEEDED);
  }

  /** @return true if the run stopped early because it was cancelled or superseded */
  private boolean writeRows(
      ExportJobId id,
      String owner,
      String period,
      long total,
      ArtifactStore.Draft draft,
      AtomicLong written) {
    return switch (settings.getReadMode()) {
      case SNAPSHOT -> snapshotRead(id, owner, period, total, draft, written);
      case CHUNKED -> chunkedRead(id, owner, period, total, draft, written);
    };
  }

  /**
   * One transaction, one cursor, one picture of the period.
   *
   * <p>The early exit is a {@link StopSignal} rather than a flag the loop checks, because the consumer handed
   * to {@link ExportSource#streamPeriod} has nowhere to return a decision to — the only way out of somebody
   * else's iteration is to throw. It is caught here and nowhere else.
   */
  private boolean snapshotRead(
      ExportJobId id,
      String owner,
      String period,
      long total,
      ArtifactStore.Draft draft,
      AtomicLong written) {
    try {
      readTransaction.executeWithoutResult(
          status ->
              source.streamPeriod(
                  period,
                  row -> {
                    draft.writeLine(csv(row));
                    if (written.incrementAndGet() % settings.getProgressInterval() == 0) {
                      progress.report(id, written.get(), total);
                      if (shouldStop(id, owner)) {
                        throw new StopSignal();
                      }
                    }
                  }));
    } catch (StopSignal signal) {
      return true;
    }
    progress.report(id, written.get(), total);
    return false;
  }

  /** Keyset pages, each its own short transaction, and no snapshot across them. */
  private boolean chunkedRead(
      ExportJobId id,
      String owner,
      String period,
      long total,
      ArtifactStore.Draft draft,
      AtomicLong written) {
    long afterId = 0;
    while (true) {
      List<ExportRowView> page = source.pageAfter(period, afterId, settings.getPageSize());
      if (page.isEmpty()) {
        progress.report(id, written.get(), total);
        return false;
      }
      for (ExportRowView row : page) {
        draft.writeLine(csv(row));
        written.incrementAndGet();
        afterId = row.id();
      }
      progress.report(id, written.get(), total);
      if (shouldStop(id, owner)) {
        return true;
      }
    }
  }

  /**
   * Two questions at once, because both mean "stop now": has somebody asked this to stop, and is the claim
   * still ours? The second is the heartbeat, and a worker that has lost its lease should stop writing rather
   * than finish a file whose outcome will be refused.
   */
  private boolean shouldStop(ExportJobId id, String owner) {
    if (jobs.isCancelRequested(id)) {
      return true;
    }
    return !claims.heartbeat(id, owner, clock.instant().plus(settings.getLease()));
  }

  private Outcome report(ReportExportOutcome outcome, Outcome success) {
    try {
      commands.send(outcome);
      return success;
    } catch (DomainException e) {
      if (e.errorCode().filter(ReconciliationErrorCode.LEASE_LOST::equals).isPresent()) {
        log.info("export {} was taken over; this run's outcome is discarded", outcome.exportId());
        return Outcome.SUPERSEDED;
      }
      throw e;
    }
  }

  private static String csv(ExportRowView row) {
    return row.id() + "," + row.orderRef() + "," + row.amountCents() + "," + row.note();
  }

  /** Kept short: this ends up in a column and in front of whoever is polling the job. */
  private static String describe(RuntimeException e) {
    return e.getClass().getSimpleName() + ": " + e.getMessage();
  }

  /** A control-flow signal, not an error; it never leaves this class. */
  private static final class StopSignal extends RuntimeException {
    private StopSignal() {
      super(null, null, false, false);
    }
  }
}
