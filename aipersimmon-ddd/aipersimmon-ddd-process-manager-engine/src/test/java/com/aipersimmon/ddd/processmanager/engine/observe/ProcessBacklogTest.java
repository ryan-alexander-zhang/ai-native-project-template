package com.aipersimmon.ddd.processmanager.engine.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.processmanager.engine.observe.ProcessBacklog.BacklogSnapshot;
import com.aipersimmon.ddd.processmanager.engine.store.ClaimedEffect;
import com.aipersimmon.ddd.processmanager.engine.store.DeadlineRow;
import com.aipersimmon.ddd.processmanager.engine.store.DeadlineStatus;
import com.aipersimmon.ddd.processmanager.engine.store.EffectStatus;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessDeadlineInsert;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessDeadlineView;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessEffectInsert;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessEffectStore;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessEffectView;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceCriteria;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceRow;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.engine.store.VersionRef;
import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * What an operator is shown as waiting. Two things matter beyond arithmetic: an age is never
 * negative however the clocks disagree, and one sample costs one pass over the store — a health
 * probe that fired a query per gauge would put load on a database precisely when it is already the
 * thing in trouble.
 */
class ProcessBacklogTest {

  private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");

  private final CountingEffects effects = new CountingEffects();
  private final CountingDeadlines deadlines = new CountingDeadlines();
  private final CountingInstances instances = new CountingInstances();

  private ProcessBacklog backlog() {
    return new ProcessBacklog(effects, deadlines, instances, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void theAgeOfTheOldestWaitingWorkIsMeasuredFromWhenItWasDue() {
    effects.oldestDue = NOW.minusSeconds(90);
    deadlines.oldestDue = NOW.minusSeconds(30);

    assertEquals(Duration.ofSeconds(90), backlog().oldestPendingEffectAge());
    assertEquals(Duration.ofSeconds(30), backlog().oldestPendingDeadlineAge());
  }

  @Test
  void nothingWaitingReadsAsZeroRatherThanAsNoReading() {
    assertEquals(Duration.ZERO, backlog().oldestPendingEffectAge(), "a gauge needs a value");
    assertEquals(Duration.ZERO, backlog().oldestPendingDeadlineAge());
  }

  @Test
  void workScheduledByAnInstanceWhoseClockRunsAheadReadsAsZeroRatherThanNegative() {
    effects.oldestDue = NOW.plusSeconds(30);
    deadlines.oldestDue = NOW.plusSeconds(30);

    // A negative age would render as a nonsense spike and could invert a threshold comparison.
    assertEquals(Duration.ZERO, backlog().oldestPendingEffectAge());
    assertEquals(Duration.ZERO, backlog().oldestPendingDeadlineAge());
  }

  @Test
  void whatIsWaitingForAnOperatorToRedriveIsCountedSeparatelyFromWhatIsStillRetrying() {
    effects.dead = 4;
    deadlines.dead = 2;

    // Dead work has stopped moving on its own: nothing will happen to it until a human acts, so
    // it is a different signal from a backlog that is merely slow.
    assertEquals(4, backlog().deadEffects());
    assertEquals(2, backlog().deadDeadlines());
    assertEquals(4, backlog().snapshot(Duration.ofMinutes(15)).deadEffects());
    assertEquals(2, backlog().snapshot(Duration.ofMinutes(15)).deadDeadlines());
  }

  @Test
  void suspendedInstancesAreCountedPerSourceAndInTotal() {
    instances.suspendedBySource = Map.of("PARKED_INPUT", 2L, "EFFECT_FAILURE", 3L);

    assertEquals(
        Map.of("PARKED_INPUT", 2L, "EFFECT_FAILURE", 3L), backlog().suspendedInstancesBySource());
    assertEquals(5, backlog().suspendedInstances(), "the total is the sum, not a separate query");
  }

  @Test
  void anInstanceCountsAsStuckRelativeToTheThresholdItWasAskedAbout() {
    backlog().stuckInstances(Duration.ofMinutes(15));

    assertEquals(
        NOW.minus(Duration.ofMinutes(15)),
        instances.stuckBefore,
        "the store is asked for instances untouched since that moment, not for a raw count");
  }

  @Test
  void oneSampleCostsOnePassOverTheStore() {
    effects.oldestDue = NOW.minusSeconds(90);
    instances.suspendedBySource = Map.of("PARKED_INPUT", 2L, "EFFECT_FAILURE", 3L);

    BacklogSnapshot snapshot = backlog().snapshot(Duration.ofMinutes(15));

    assertEquals(1, instances.suspendedQueries.get(), "grouped once, not once per source");
    assertEquals(1, effects.oldestDueQueries.get());
    assertEquals(Duration.ofSeconds(90), snapshot.oldestPendingEffectAge());
    assertEquals(5, snapshot.suspendedInstances());
    assertEquals(Duration.ZERO, snapshot.oldestPendingDeadlineAge());
  }

  @Test
  void aSnapshotCannotBeChangedThroughTheMapItWasBuiltFrom() {
    Map<String, Long> mutable = new HashMap<>(Map.of("PARKED_INPUT", 2L));
    instances.suspendedBySource = mutable;

    BacklogSnapshot snapshot = backlog().snapshot(Duration.ofMinutes(15));
    mutable.put("EFFECT_FAILURE", 9L);

    assertEquals(Map.of("PARKED_INPUT", 2L), snapshot.suspendedBySource(), "it is a copy");
    assertThrows(
        UnsupportedOperationException.class, () -> snapshot.suspendedBySource().put("x", 1L));
  }

  // --- test doubles ----------------------------------------------------------------------------
  // Only the aggregate reads a backlog sample needs are implemented. Everything else throws, so a
  // future read that quietly starts hitting the store during a health probe shows up here rather
  // than passing unnoticed — the point of the class being a one-pass sample in the first place.

  private static final class CountingEffects implements ProcessEffectStore {
    private Instant oldestDue;
    private long dead;
    private final AtomicInteger oldestDueQueries = new AtomicInteger();

    @Override
    public long countDead() {
      return dead;
    }

    @Override
    public Optional<Instant> oldestDuePending(Instant now) {
      oldestDueQueries.incrementAndGet();
      return Optional.ofNullable(oldestDue);
    }

    @Override
    public long nextSeq(ProcessInstanceId instanceId) {
      throw notASample();
    }

    @Override
    public void insert(ProcessEffectInsert effect, Instant now) {
      throw notASample();
    }

    @Override
    public Optional<ClaimedEffect> load(String effectId) {
      throw notASample();
    }

    @Override
    public int markDelivered(String effectId, String leaseToken, Instant now) {
      throw notASample();
    }

    @Override
    public int scheduleRetry(
        String effectId, String leaseToken, Instant nextAttemptAt, String error, Instant now) {
      throw notASample();
    }

    @Override
    public int markDead(String effectId, String leaseToken, String error, Instant now) {
      throw notASample();
    }

    @Override
    public int markCancelled(String effectId, String leaseToken, Instant now) {
      throw notASample();
    }

    @Override
    public int redrive(String effectId, Instant now) {
      throw notASample();
    }

    @Override
    public int cancelPending(ProcessInstanceId instanceId, Instant now) {
      throw notASample();
    }

    @Override
    public long countDead(ProcessInstanceId instanceId) {
      throw notASample();
    }

    @Override
    public List<ProcessEffectView> byStatus(EffectStatus status, int limit) {
      throw notASample();
    }
  }

  private static final class CountingDeadlines implements ProcessDeadlineStore {
    private Instant oldestDue;
    private long dead;

    @Override
    public long countDead() {
      return dead;
    }

    @Override
    public Optional<Instant> oldestDuePending(Instant now) {
      return Optional.ofNullable(oldestDue);
    }

    @Override
    public long nextGeneration(ProcessInstanceId instanceId, DeadlineName name) {
      throw notASample();
    }

    @Override
    public long currentGeneration(ProcessInstanceId instanceId, DeadlineName name) {
      throw notASample();
    }

    @Override
    public void schedule(ProcessDeadlineInsert deadline, Instant now) {
      throw notASample();
    }

    @Override
    public void cancelCurrent(ProcessInstanceId instanceId, DeadlineName name, Instant now) {
      throw notASample();
    }

    @Override
    public int cancelLive(ProcessInstanceId instanceId, Instant now) {
      throw notASample();
    }

    @Override
    public int cancelClaimed(String deadlineId, String leaseToken, Instant now) {
      throw notASample();
    }

    @Override
    public Optional<DeadlineStatus> statusForUpdate(String deadlineId) {
      throw notASample();
    }

    @Override
    public Optional<DeadlineRow> load(String deadlineId) {
      throw notASample();
    }

    @Override
    public int markFired(String deadlineId, String leaseToken, Instant now) {
      throw notASample();
    }

    @Override
    public int scheduleRetry(
        String deadlineId, String leaseToken, Instant nextAttemptAt, String error, Instant now) {
      throw notASample();
    }

    @Override
    public int markDead(String deadlineId, String leaseToken, String error, Instant now) {
      throw notASample();
    }

    @Override
    public int redrive(String deadlineId, Instant now) {
      throw notASample();
    }

    @Override
    public long countDead(ProcessInstanceId instanceId) {
      throw notASample();
    }

    @Override
    public List<ProcessDeadlineView> byStatus(DeadlineStatus status, int limit) {
      throw notASample();
    }
  }

  private static final class CountingInstances implements ProcessInstanceStore {
    private Map<String, Long> suspendedBySource = Map.of();
    private Instant stuckBefore;
    private final AtomicInteger suspendedQueries = new AtomicInteger();

    @Override
    public Map<String, Long> countSuspendedBySource() {
      suspendedQueries.incrementAndGet();
      return suspendedBySource;
    }

    @Override
    public long countStuck(Instant updatedBefore) {
      stuckBefore = updatedBefore;
      return 0;
    }

    @Override
    public Optional<ProcessInstanceRow> find(ProcessInstanceId instanceId) {
      throw notASample();
    }

    @Override
    public Optional<ProcessInstanceRow> findForUpdate(ProcessInstanceId instanceId) {
      throw notASample();
    }

    @Override
    public Optional<ProcessInstanceRow> findByBusinessKey(
        String tenantId, ProcessType processType, ProcessBusinessKey businessKey) {
      throw notASample();
    }

    @Override
    public Optional<ProcessInstanceRow> readByBusinessKey(
        String tenantId, ProcessType processType, ProcessBusinessKey businessKey) {
      throw notASample();
    }

    @Override
    public void insert(ProcessInstanceRow row, Instant now) {
      throw notASample();
    }

    @Override
    public int updateSnapshot(
        ProcessInstanceRow row, ProcessRevision expectedRevision, Instant now) {
      throw notASample();
    }

    @Override
    public void resume(ProcessInstanceId instanceId, ProcessLifecycle toLifecycle, Instant now) {
      throw notASample();
    }

    @Override
    public void suspend(
        ProcessInstanceId instanceId,
        ProcessLifecycle resumeLifecycle,
        String reason,
        String source,
        String workId,
        Instant now) {
      throw notASample();
    }

    @Override
    public List<ProcessInstanceRow> search(
        ProcessInstanceCriteria criteria, int limit, int offset) {
      throw notASample();
    }

    @Override
    public List<ProcessInstanceRow> findStuck(Instant updatedBefore, int limit) {
      throw notASample();
    }

    @Override
    public List<VersionRef> distinctVersionsInUse() {
      throw notASample();
    }
  }

  private static UnsupportedOperationException notASample() {
    return new UnsupportedOperationException("a backlog sample must not reach for this");
  }
}
