package com.example.samples.s28.reconciliation.application;

import com.example.samples.s28.reconciliation.domain.ExportJobId;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Claim one, run one. The whole queue, and there is deliberately very little of it.
 *
 * <p>Every instance runs this, with no distributed lock around it, for the reason the library gives about its
 * own relays and S11 repeats: a lock would put the queue behind a single holder, and an instance killed while
 * holding it releases nothing, so every other instance would stop draining until the lock expired. What makes
 * running everywhere safe is that the <em>work</em> is claimed one job at a time, and the claim is atomic.
 *
 * <p>One job per tick, not "everything claimable". A batch claim would be fewer round trips and would also
 * mean that an instance dying with ten jobs claimed leaves ten jobs waiting out a lease instead of one.
 */
@Component
public class ExportWorker {

  private static final Logger log = LoggerFactory.getLogger(ExportWorker.class);

  private final ExportClaims claims;
  private final ExportRunner runner;
  private final ExportSettings settings;
  private final Clock clock;
  private final String owner;

  ExportWorker(ExportClaims claims, ExportRunner runner, ExportSettings settings, Clock clock) {
    this.claims = claims;
    this.runner = runner;
    this.settings = settings;
    this.clock = clock;
    this.owner = WorkerIdentity.ofThisInstance();
  }

  /** Who this instance is, as far as a claim is concerned. */
  public String owner() {
    return owner;
  }

  /**
   * Claim and run at most one job.
   *
   * @return the job that was run, or empty if the queue had nothing claimable
   */
  public Optional<ExportJobId> runOne() {
    Optional<ExportJobId> claimed =
        claims.claimNext(owner, settings.getLease(), clock.instant());
    claimed.ifPresent(
        id -> {
          ExportRunner.Outcome outcome = runner.run(id, owner);
          log.info("export {} finished as {}", id, outcome);
        });
    return claimed;
  }
}
