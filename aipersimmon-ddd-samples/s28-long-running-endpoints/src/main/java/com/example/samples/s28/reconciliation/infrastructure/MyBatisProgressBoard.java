package com.example.samples.s28.reconciliation.infrastructure;

import com.example.samples.s28.reconciliation.application.ExportProgress;
import com.example.samples.s28.reconciliation.application.ProgressBoard;
import com.example.samples.s28.reconciliation.domain.ExportJobId;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Progress, published on a connection of its own — and the alternative, kept next to it.
 *
 * <p>The two nested classes differ in one annotation, and that annotation is the whole difference between a
 * progress query that answers and one that does not. Which is why the sample ships both rather than describing
 * the wrong one: {@link SameTransaction} is what most implementations of this end up being, it is not obviously
 * broken on inspection, and its failure mode — nothing at all until the work is finished — looks exactly like a
 * job that is not running.
 *
 * <p>Selected by {@code s28.export.progress-transaction}. The default is {@link OwnTransaction}; the other is
 * reachable only by asking for it, so it cannot be arrived at by accident.
 */
final class MyBatisProgressBoard {

  private MyBatisProgressBoard() {}

  /**
   * Shared by both variants; only the transaction attributes differ.
   *
   * <p><strong>Nothing here may be {@code final}.</strong> {@code OwnTransaction} carries {@code @Transactional}, so
   * Spring wraps it in a CGLIB subclass — and a CGLIB proxy is instantiated without running any constructor, so its
   * own copies of these fields are null. Non-final methods are overridden and delegated to the properly constructed
   * target; a {@code final} one cannot be, so it runs on the proxy and throws a {@code NullPointerException} on the
   * first field it touches. The compiler is happy, the context starts, and the first read fails.
   */
  abstract static class Base implements ProgressBoard {

    private final ProgressMapper mapper;
    private final Clock clock;

    Base(ProgressMapper mapper, Clock clock) {
      this.mapper = mapper;
      this.clock = clock;
    }

    void write(ExportJobId id, long rowsDone, Long total) {
      mapper.upsert(id.value(), rowsDone, total, Timestamp.from(clock.instant()));
    }

    @Override
    public Optional<ExportProgress> of(ExportJobId id) {
      ProgressReading reading = mapper.read(id.value());
      return reading == null
          ? Optional.empty()
          : Optional.of(
              new ExportProgress(
                  reading.getRowsDone(), reading.getRowsTotal(), reading.getUpdatedAt()));
    }

    @Override
    public void forget(ExportJobId id) {
      mapper.delete(id.value());
    }
  }

  /**
   * Each tick on its own connection, committed immediately.
   *
   * <p>{@code REQUIRES_NEW} suspends whatever the caller is in, which for the export means the read transaction
   * holding the cursor. So a running export holds two pool connections while it ticks rather than one — a real
   * cost, and cheap next to the alternative of publishing nothing.
   *
   * <p>It also means progress survives a rollback of the export, which is correct: the rows really were read, and
   * a failed job's last known position is exactly what somebody investigating it wants.
   */
  @Component
  @ConditionalOnProperty(
      name = "s28.export.progress-transaction",
      havingValue = "OWN_TRANSACTION",
      matchIfMissing = true)
  static class OwnTransaction extends Base {

    OwnTransaction(ProgressMapper mapper, Clock clock) {
      super(mapper, clock);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void report(ExportJobId id, long rowsDone, Long total) {
      write(id, rowsDone, total);
    }
  }

  /**
   * Joins whatever transaction is already open. The counterexample, reachable only on request.
   *
   * <p>What it costs: the ticks are invisible to every other connection until the export's transaction commits, at
   * which point the export is over and the progress is history. A client polls, sees nothing, and concludes the job
   * is stuck.
   *
   * <p>There is a second cost that shows up first, and it is the more interesting one. A pure read of a million
   * rows wants its transaction marked read-only, and PostgreSQL refuses every write on a read-only connection — so
   * this mode cannot coexist with the flag the export otherwise deserves. {@code ExportRunner} takes the flag off
   * when this mode is selected, which is worth noticing as a general fact rather than a sample quirk: <em>the
   * moment a long read reports on itself, it stops being a read</em>, unless the reporting goes elsewhere.
   */
  @Component
  @ConditionalOnProperty(name = "s28.export.progress-transaction", havingValue = "SAME_TRANSACTION")
  static class SameTransaction extends Base {

    SameTransaction(ProgressMapper mapper, Clock clock) {
      super(mapper, clock);
    }

    @Override
    public void report(ExportJobId id, long rowsDone, Long total) {
      write(id, rowsDone, total);
    }
  }
}
