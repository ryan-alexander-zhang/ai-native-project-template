package com.aipersimmon.ddd.outbox.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.FailureClassifier;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.aipersimmon.ddd.outbox.RetryBackoff;
import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay;
import com.aipersimmon.ddd.outbox.engine.relay.RelayLeases;
import com.aipersimmon.ddd.outbox.engine.store.OutboxLease;
import com.aipersimmon.ddd.outbox.engine.store.OutboxStore;
import com.aipersimmon.ddd.outbox.engine.store.PendingMessage;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The relay's mutual exclusion lives on the row, not on the schedule. These are the properties that
 * change rests on: two instances polling at the same instant take disjoint work, a killed
 * instance's rows come back on their own, and an aggregate still goes out in order even though
 * nothing coordinates the pollers.
 */
@SpringBootTest(
    classes = OutboxRelayClaimTest.TestApp.class,
    properties = {
      "aipersimmon.ddd.outbox.relay.enabled=false",
      "aipersimmon.ddd.outbox.retry.base-backoff-ms=0",
      "aipersimmon.ddd.outbox.retry.max-backoff-ms=0"
    })
class OutboxRelayClaimTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {

    @Bean
    RecordingDispatcher recordingDispatcher() {
      return new RecordingDispatcher();
    }
  }

  /** Records what it was handed, and can be told to advance a clock on every dispatch. */
  static class RecordingDispatcher implements OutboxDispatcher {
    final List<String> dispatched = new CopyOnWriteArrayList<>();
    SteppingClock clock;
    Duration step = Duration.ZERO;

    @Override
    public void dispatch(OutboxMessage message) {
      dispatched.add(message.eventId());
      if (clock != null) {
        clock.advance(step);
      }
    }
  }

  /** A clock the test moves by hand, so a poll's time budget can be made to run out. */
  static final class SteppingClock extends Clock {
    private Instant now;

    SteppingClock(Instant start) {
      this.now = start;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  @Autowired OutboxStore store;
  @Autowired OutboxRelay relay;
  @Autowired DeadLetterStore deadLetterStore;
  @Autowired FailureClassifier failureClassifier;
  @Autowired RecordingDispatcher dispatcher;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM aipersimmon_outbox");
    jdbc.update("DELETE FROM aipersimmon_dead_letter");
    dispatcher.dispatched.clear();
    dispatcher.clock = null;
    dispatcher.step = Duration.ZERO;
  }

  private void insert(String eventId, String subject, long createdOffsetSeconds) {
    jdbc.update(
        "INSERT INTO aipersimmon_outbox (event_id, source, type, version, payload, occurred_at, "
            + "subject, correlation_id, causation_id, sent, attempts, created_at) "
            + "VALUES (?, 'test', 'SampleEvent', 1, '{}', ?, ?, 'corr', NULL, ?, 0, ?)",
        eventId,
        Timestamp.from(Instant.now()),
        subject,
        false,
        Timestamp.from(Instant.now().plusSeconds(createdOffsetSeconds)));
  }

  private static OutboxLease lease(String owner, Instant until) {
    return new OutboxLease(owner, owner + "-token", until);
  }

  private static List<String> ids(List<PendingMessage> claimed) {
    return claimed.stream().map(p -> p.message().eventId()).toList();
  }

  private String leaseTokenOf(String eventId) {
    return jdbc.queryForObject(
        "SELECT lease_token FROM aipersimmon_outbox WHERE event_id = ?", String.class, eventId);
  }

  @Test
  void twoInstancesClaimingAtTheSameInstantTakeDisjointRows() {
    insert("a1", null, 0);
    insert("b1", null, 1);
    Instant now = Instant.now();

    List<PendingMessage> first = store.claimDue(now, 10, 1, lease("node-A", now.plusSeconds(60)));
    List<PendingMessage> second = store.claimDue(now, 10, 10, lease("node-B", now.plusSeconds(60)));

    assertEquals(List.of("a1"), ids(first), "the first claim takes the oldest row");
    assertEquals(
        List.of("b1"),
        ids(second),
        "a concurrent claim must never be handed a row the first one already holds");
  }

  @Test
  void aRowHeldByAKilledInstanceIsClaimableOnceItsLeaseExpires() {
    insert("e1", "agg-1", 0);
    Instant now = Instant.now();
    // A killed instance releases nothing, so its lease simply runs out where it stands.
    store.claimDue(now, 10, 10, lease("node-A", now.minusSeconds(1)));

    List<PendingMessage> reclaimed =
        store.claimDue(now, 10, 10, lease("node-B", now.plusSeconds(60)));

    assertEquals(
        List.of("e1"),
        ids(reclaimed),
        "an expired lease is the whole recovery mechanism: nothing has to notice the death");
    assertEquals("node-B-token", leaseTokenOf("e1"), "and the new holder owns it outright");
  }

  @Test
  void onlyTheHeadOfAnAggregateIsClaimableEvenWhenLaterEventsAreDueToo() {
    insert("e1", "agg-1", 0);
    insert("e2", "agg-1", 10);
    insert("e3", "agg-1", 20);
    Instant now = Instant.now();

    List<PendingMessage> claimed =
        store.claimDue(now, 10, 10, lease("node-A", now.plusSeconds(60)));

    assertEquals(
        List.of("e1"),
        ids(claimed),
        "admitting only the head is what keeps an aggregate ordered without coordinating pollers");
    assertNull(leaseTokenOf("e2"), "a blocked row is not leased either — it was never a candidate");
  }

  @Test
  void anAggregatesNextEventBecomesClaimableOnlyOnceItsPredecessorIsSent() {
    insert("e1", "agg-1", 0);
    insert("e2", "agg-1", 10);
    Instant now = Instant.now();
    store.claimDue(now, 10, 10, lease("node-A", now.plusSeconds(60)));

    assertEquals(
        List.of(),
        ids(store.claimDue(now, 10, 10, lease("node-B", now.plusSeconds(60)))),
        "while the head is live no other instance may take the aggregate's later events");

    store.markSent(List.of("e1"), now);

    assertEquals(
        List.of("e2"),
        ids(store.claimDue(now, 10, 10, lease("node-B", now.plusSeconds(60)))),
        "once the head is sent its successor is the head, and any instance may take it");
  }

  @Test
  void everyRelayWriteEndsTheClaimSoNoRowWaitsOutALeaseItNoLongerNeeds() {
    insert("sent-1", null, 0);
    insert("retried-1", null, 1);
    insert("released-1", null, 2);
    Instant now = Instant.now();
    store.claimDue(now, 10, 10, lease("node-A", now.plusSeconds(600)));

    store.markSent(List.of("sent-1"), now);
    store.scheduleRetry("retried-1", now.plusSeconds(5));
    store.release(List.of("released-1"));

    assertNull(leaseTokenOf("sent-1"), "a delivered row holds nothing");
    assertNull(leaseTokenOf("retried-1"), "nor does one that is backing off");
    assertNull(leaseTokenOf("released-1"), "nor one handed back");
    assertEquals(
        List.of("released-1"),
        ids(store.claimDue(now, 10, 10, lease("node-B", now.plusSeconds(60)))),
        "a released row is claimable at once, while the backing-off one waits for its next attempt");
  }

  @Test
  void onePollDrainsSeveralEventsOfTheSameAggregateInOrder() {
    insert("e1", "agg-1", 0);
    insert("e2", "agg-1", 10);
    insert("e3", "agg-1", 20);

    relay.relay();

    assertEquals(
        List.of("e1", "e2", "e3"),
        dispatcher.dispatched,
        "claiming only the head must not cost a busy aggregate its throughput: the poll claims "
            + "again as each row is sent, and still delivers in order");
  }

  @Test
  void aPollThatSpendsItsTimeBudgetHandsBackWhatItDidNotDispatch() {
    insert("e1", null, 0);
    insert("e2", null, 1);
    insert("e3", null, 2);
    SteppingClock clock = new SteppingClock(Instant.parse("2026-07-29T10:00:00Z"));
    dispatcher.clock = clock;
    dispatcher.step = Duration.ofSeconds(30);
    // A two-second lease gives the poll one second of budget; one dispatch then overruns it.
    OutboxRelay bounded =
        new OutboxRelay(
            store,
            dispatcher,
            deadLetterStore,
            failureClassifier,
            new RetryBackoff(0, 0),
            clock,
            10,
            10,
            RelayLeases.ownedBy("node-A", Duration.ofSeconds(2)));

    bounded.relay();

    assertEquals(
        List.of("e1"),
        dispatcher.dispatched,
        "the poll stops once its budget is gone rather than running on past its own lease");
    assertNull(leaseTokenOf("e2"), "the rows it never reached are handed back, not left leased");
    assertNull(leaseTokenOf("e3"));
    assertTrue(
        ids(store.claimDue(
                clock.instant(), 10, 10, lease("node-B", clock.instant().plusSeconds(60))))
            .containsAll(List.of("e2", "e3")),
        "so another instance can pick them up immediately");
  }
}
