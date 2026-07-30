package com.aipersimmon.ddd.processmanager.engine.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessEffectStore;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessRetentionStore;
import com.aipersimmon.ddd.processmanager.engine.store.RollingBackUnitOfWork;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the purge decides, as distinct from what the SQL selects.
 *
 * <p>The eligibility predicate lives in each backend's SQL and is pinned there, against a real
 * database, because that is where it runs. What is left to decide here is everything around it: how
 * much is taken at once, that selecting and deleting happen in one transaction, and that a run
 * which only kept up says so rather than looking finished.
 */
class ProcessCleanupTest {

  private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");

  private final InMemoryProcessInstanceStore instances = new InMemoryProcessInstanceStore();
  private final InMemoryProcessTransitionStore transitions =
      new InMemoryProcessTransitionStore(instances);
  private final InMemoryProcessEffectStore effects = new InMemoryProcessEffectStore();
  private final InMemoryProcessDeadlineStore deadlines = new InMemoryProcessDeadlineStore();
  private final RollingBackUnitOfWork unitOfWork =
      new RollingBackUnitOfWork(instances, transitions, effects, deadlines);

  private static final Clock CLOCK =
      new Clock() {
        @Override
        public Instant instant() {
          return NOW;
        }

        @Override
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return this;
        }
      };

  /** Records what it was asked and hands back a scripted worklist. */
  private static final class RecordingRetention implements ProcessRetentionStore {
    private final List<Instant> cutoffs = new ArrayList<>();
    private final List<Integer> limits = new ArrayList<>();
    private final List<List<ProcessInstanceId>> purged = new ArrayList<>();
    private List<ProcessInstanceId> purgeable = List.of();
    private Runnable betweenFindAndPurge = () -> {};

    @Override
    public List<ProcessInstanceId> findPurgeable(Instant endedBefore, int limit) {
      cutoffs.add(endedBefore);
      limits.add(limit);
      betweenFindAndPurge.run();
      return purgeable.size() > limit ? purgeable.subList(0, limit) : purgeable;
    }

    @Override
    public int purge(List<ProcessInstanceId> instanceIds) {
      purged.add(List.copyOf(instanceIds));
      return instanceIds.size();
    }
  }

  private final RecordingRetention retention = new RecordingRetention();

  private ProcessCleanup cleanup(long retentionSeconds, int batchSize) {
    return new ProcessCleanup(retention, unitOfWork, CLOCK, retentionSeconds, batchSize);
  }

  private static List<ProcessInstanceId> ids(int count) {
    List<ProcessInstanceId> ids = new ArrayList<>();
    for (int i = 1; i <= count; i++) {
      ids.add(new ProcessInstanceId("instance-" + i));
    }
    return ids;
  }

  @Test
  void theCutoffIsNowMinusTheRetentionWindow() {
    cleanup(Duration.ofDays(30).toSeconds(), 200).purge();

    assertEquals(List.of(NOW.minus(Duration.ofDays(30))), retention.cutoffs);
  }

  @Test
  void aPurgeRemovesExactlyTheInstancesItWasOffered() {
    retention.purgeable = ids(3);

    assertEquals(3, cleanup(60, 200).purge());

    assertEquals(List.of(ids(3)), retention.purged);
  }

  @Test
  void nothingToRemoveCostsOneQueryAndNoDelete() {
    assertEquals(0, cleanup(60, 200).purge());

    // Issuing a delete with an empty id list would be a statement that can only match nothing —
    // and on some drivers an `IN ()` that does not parse at all.
    assertTrue(retention.purged.isEmpty());
  }

  @Test
  void aRunTakesAtMostItsBatch() {
    retention.purgeable = ids(10);

    assertEquals(4, cleanup(60, 4).purge());

    assertEquals(
        List.of(4), retention.limits, "the bound is asked of the store, not trimmed after");
    assertEquals(4, retention.purged.get(0).size());
  }

  @Test
  void selectingAndDeletingHappenInOneTransaction() {
    retention.purgeable = ids(2);
    List<Boolean> insideTransaction = new ArrayList<>();
    retention.betweenFindAndPurge = () -> insideTransaction.add(unitOfWork.inExistingTransaction());

    cleanup(60, 200).purge();

    // Between the two, a relay could otherwise stage an effect on an instance this run had already
    // judged finished — and the delete would take it with the instance, sending nothing and
    // leaving no trace that anything was owed.
    assertEquals(List.of(true), insideTransaction);
  }
}
