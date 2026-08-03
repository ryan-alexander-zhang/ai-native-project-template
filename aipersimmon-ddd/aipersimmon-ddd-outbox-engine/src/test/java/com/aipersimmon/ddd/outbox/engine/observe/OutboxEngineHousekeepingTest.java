package com.aipersimmon.ddd.outbox.engine.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.outbox.engine.cleanup.OutboxCleanup;
import com.aipersimmon.ddd.outbox.engine.relay.RelayLeases;
import com.aipersimmon.ddd.outbox.engine.store.InMemoryOutboxStore;
import com.aipersimmon.ddd.outbox.engine.store.OutboxInsert;
import com.aipersimmon.ddd.outbox.engine.store.OutboxLease;
import com.aipersimmon.ddd.outbox.engine.store.PendingBacklog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The engine's smaller pieces: what a claim stamps on a row, what an operator is shown as waiting,
 * and what retention removes. Each is short, and each was previously reachable only through a
 * database.
 */
class OutboxEngineHousekeepingTest {

  private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");
  private static final int MAX_ATTEMPTS = 3;

  private final InMemoryOutboxStore store = new InMemoryOutboxStore();

  private void insert(String eventId, Instant createdAt) {
    store.insert(
        new OutboxInsert(
            eventId,
            "/test",
            "SampleEvent",
            1,
            "{}",
            createdAt,
            null,
            "acme",
            "corr",
            null,
            null,
            null,
            null,
            createdAt));
  }

  // --- leases ----------------------------------------------------------------------------------

  @Test
  void everyClaimGetsItsOwnTokenSoTwoPollsCanNeverReadBackEachOthersRows() {
    RelayLeases leases = RelayLeases.ownedBy("node-A", Duration.ofMinutes(5));

    OutboxLease first = leases.next(NOW);
    OutboxLease second = leases.next(NOW);

    assertEquals("node-A", first.owner());
    assertNotEquals(
        first.token(),
        second.token(),
        "the token is how a claim reads back exactly the rows it won, so it cannot be reused");
    assertEquals(NOW.plus(Duration.ofMinutes(5)), first.until());
  }

  @Test
  void aWorkerThatWasNotNamedStillIdentifiesItselfSoAHeldRowCanBeTracedToAnInstance() {
    assertFalse(
        RelayLeases.forThisProcess(Duration.ofMinutes(5)).next(NOW).owner().isBlank(),
        "an unnamed owner would make a stuck lease untraceable to the instance holding it");
  }

  @Test
  void aLeaseMustNameAnOwnerATokenAndAnExpiry() {
    assertThrows(
        IllegalArgumentException.class, () -> new OutboxLease(" ", "token", NOW.plusSeconds(1)));
    assertThrows(
        IllegalArgumentException.class, () -> new OutboxLease("node-A", "", NOW.plusSeconds(1)));
    assertThrows(IllegalArgumentException.class, () -> new OutboxLease("node-A", "token", null));
  }

  // --- backlog ---------------------------------------------------------------------------------

  @Test
  void theBacklogCountsOnlyWhatTheRelayStillMeansToDeliver() {
    insert("waiting", NOW.minusSeconds(60));
    insert("delivered", NOW.minusSeconds(3600));
    store.markSent(List.of("delivered"), NOW);
    insert("given-up", NOW.minusSeconds(7200));
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      store.scheduleRetry("given-up", NOW);
    }

    OutboxBacklog.Snapshot backlog =
        new OutboxBacklog(store, Clock.fixed(NOW, ZoneOffset.UTC), MAX_ATTEMPTS).snapshot();

    // A delivered row is finished and a spent one belongs to the dead-letter table. Counting
    // either would have the gauge cry wolf forever.
    assertEquals(1, backlog.pending());
    assertEquals(
        1,
        backlog.givenUp(),
        "but a given-up row must not be invisible either: it is unsent, unclaimed and "
            + "un-dead-lettered — stranded — and this gauge is the only thing that shows it");
    assertEquals(
        Duration.ofSeconds(60),
        backlog.oldestPendingAge(),
        "and the age is the oldest waiting row's, which is the reading worth alerting on: fifty "
            + "rows an hour old is a stopped relay, a thousand rows seconds old is a busy system");
  }

  @Test
  void anEmptyOutboxReadsAsZeroRatherThanAsNoReading() {
    OutboxBacklog.Snapshot backlog =
        new OutboxBacklog(store, Clock.fixed(NOW, ZoneOffset.UTC), MAX_ATTEMPTS).snapshot();

    assertEquals(0, backlog.pending());
    assertEquals(0, backlog.givenUp());
    assertEquals(Duration.ZERO, backlog.oldestPendingAge(), "a gauge needs a value to alert on");
  }

  @Test
  void aRowWrittenByAnInstanceWhoseClockRunsAheadReadsAsZeroAgeRatherThanNegative() {
    insert("from-the-future", NOW.plusSeconds(30));

    OutboxBacklog.Snapshot backlog =
        new OutboxBacklog(store, Clock.fixed(NOW, ZoneOffset.UTC), MAX_ATTEMPTS).snapshot();

    assertEquals(Duration.ZERO, backlog.oldestPendingAge());
  }

  @Test
  void anEmptyBacklogHasNoOldestRow() {
    assertEquals(0, PendingBacklog.EMPTY.rows());
    assertEquals(null, PendingBacklog.EMPTY.oldestCreatedAt());
  }

  // --- retention -------------------------------------------------------------------------------

  @Test
  void retentionRemovesDeliveredRowsPastTheirKeepingTimeAndNothingElse() {
    insert("old-and-sent", NOW.minusSeconds(7200));
    insert("just-sent", NOW.minusSeconds(10));
    insert("never-sent", NOW.minusSeconds(7200));
    store.markSent(List.of("old-and-sent"), NOW.minusSeconds(3600));
    store.markSent(List.of("just-sent"), NOW.minusSeconds(10));

    new OutboxCleanup(store, Clock.fixed(NOW, ZoneOffset.UTC), 600, 500).purge();

    assertEquals(
        List.of("just-sent", "never-sent"),
        store.eventIds(),
        "retention is about delivered rows outliving their usefulness; an undelivered row is live "
            + "work and must never be swept up with them");
  }

  @Test
  void aRetentionSweepThatFindsNothingIsNotAnError() {
    new OutboxCleanup(store, Clock.fixed(NOW, ZoneOffset.UTC), 600, 500).purge();

    assertTrue(store.eventIds().isEmpty());
  }

  @Test
  void aBacklogLargerThanOnePageIsDrainedByLoopingPagesNotByOneGiantDelete() {
    for (int i = 0; i < 3; i++) {
      insert("expired-" + i, NOW.minusSeconds(7200));
    }
    store.markSent(List.of("expired-0", "expired-1", "expired-2"), NOW.minusSeconds(3600));

    // batchSize=1: one run must still remove everything, by paging — the in-memory store honours
    // the page bound, so a purge that issued a single unbounded delete could not pass this with
    // the loop removed... and a purge that ran only one page would leave two rows behind.
    new OutboxCleanup(store, Clock.fixed(NOW, ZoneOffset.UTC), 600, 1).purge();

    assertTrue(store.eventIds().isEmpty(), "all pages drained in one scheduled run");
  }
}
