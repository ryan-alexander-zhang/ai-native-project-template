package com.example.samples.s11.ordering.application;

import com.aipersimmon.ddd.application.ConcurrencyConflictException;
import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The work a schedule triggers — and it is only the work. There is no {@code @Scheduled} here, so the
 * same method serves the timer, an operator's endpoint and a test. That split is the one the library
 * makes for its own outbox relay, for the reason its javadoc gives: with the trigger on a separate
 * bean, "a deployment that relays from one dedicated instance, or a test that drives delivery itself,
 * turns the trigger off and calls {@code relay()} directly".
 *
 * <p><strong>Find, then send one command each.</strong> Not one {@code UPDATE ... WHERE due_at < now}.
 * The statement is faster and wrong: it writes past the rule that says only an open order closes, it
 * emits no event so nothing downstream reacts, it bypasses the version predicate that makes two
 * instances safe, and it takes the whole backlog in one transaction so one bad row loses all of them.
 * {@code BulkCloser} in the infrastructure package is that statement, kept as a counterexample a test
 * points at.
 *
 * <p><strong>One command per order, so failure is per order.</strong> Each dispatch is its own
 * transaction (the bus opens it), so 37 failures out of 1000 leave 963 committed. A single
 * batch-shaped command would roll all 1000 back — and then retry all 1000.
 *
 * <p><strong>Where the context comes from with no request.</strong> Two things a scheduled thread does
 * not inherit:
 *
 * <ul>
 *   <li><em>The tenant.</em> {@code TenantContext} is bound at a trusted boundary — an HTTP filter, a
 *       consumer entry — and a timer thread has neither. Read here through {@code effective()}, which
 *       makes the "nothing is bound" decision once from the deployment's mode: the {@code __root__}
 *       sentinel in a single-tenant deployment, and a {@code MissingTenantException} in a
 *       multi-tenant one rather than quietly sweeping one bucket's rows as another's. A multi-tenant
 *       sweep therefore loops tenants and wraps each round in {@code TenantContext.runAs}.
 *   <li><em>The causal chain.</em> {@code send(command)} would mint a fresh root per order, leaving
 *       1000 unrelated correlation ids and no way to ask "what did the 03:15 round do". So the round
 *       mints one root context and passes it as the cause of every command: they share its
 *       correlationId and record it as their causation, while the bus still mints each command's own
 *       message id. The round becomes the causal root — which is what it is.
 * </ul>
 */
@Component
public class ExpiredOrderSweep {

  private static final Logger log = LoggerFactory.getLogger(ExpiredOrderSweep.class);

  private final ExpiredOrders expiredOrders;
  private final CommandBus commandBus;
  private final IdGenerator idGenerator;
  private final Clock clock;
  private final int batchSize;

  ExpiredOrderSweep(
      ExpiredOrders expiredOrders,
      CommandBus commandBus,
      IdGenerator idGenerator,
      Clock clock,
      @Value("${ordering.sweep.batch-size:100}") int batchSize) {
    this.expiredOrders = expiredOrders;
    this.commandBus = commandBus;
    this.idGenerator = idGenerator;
    this.clock = clock;
    this.batchSize = batchSize;
  }

  /** One bounded round. Safe to call from anywhere, as often as anyone likes. */
  public SweepReport sweepOnce() {
    Instant asOf = clock.instant();
    List<String> candidates = expiredOrders.findExpired(asOf, batchSize);
    CommandContext round = CommandContext.root(TenantContext.effective(), idGenerator.newId());

    List<SweepReport.Failure> failures = new ArrayList<>();
    int closed = 0;
    int skipped = 0;
    for (String orderId : candidates) {
      try {
        commandBus.send(new CloseExpiredOrder(orderId), round);
        closed++;
      } catch (DomainException
          | EntityNotFoundException
          | ConcurrencyConflictException refused) {
        // The scan was advisory and the aggregate disagreed: paid, closed by hand, gone, or written
        // by another instance one moment ago (that last one arrives as a version conflict). Nothing
        // is wrong, and the next round will not see it — so it is a skip, not a failure. A job that
        // reports these as failures teaches its operators to ignore failures.
        skipped++;
      } catch (RuntimeException failed) {
        // Something actually broke. Record it and keep going — one bad order must not stop the
        // round, and the ones already closed are already committed.
        failures.add(new SweepReport.Failure(orderId, failed.getClass().getSimpleName()));
        log.warn("sweep {} could not close order {}", round.correlationId(), orderId, failed);
      }
    }

    SweepReport report =
        new SweepReport(round.correlationId(), candidates.size(), closed, skipped, failures);
    log.info(
        "sweep {} scanned {} closed {} skipped {} failed {}",
        report.runId(),
        report.scanned(),
        report.closed(),
        report.skipped(),
        report.failures().size());
    return report;
  }
}
