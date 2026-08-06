package com.aipersimmon.ddd.outbox.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.observability.NoOpStoreAndForwardTracer;
import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.FailureClassifier;
import com.aipersimmon.ddd.outbox.InFlightDispatch;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.aipersimmon.ddd.outbox.RetryBackoff;
import com.aipersimmon.ddd.outbox.engine.observe.OutboxObserver;
import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay;
import com.aipersimmon.ddd.outbox.engine.relay.RelayLeases;
import com.aipersimmon.ddd.outbox.engine.store.OutboxInsert;
import com.aipersimmon.ddd.outbox.engine.store.OutboxLease;
import com.aipersimmon.ddd.outbox.engine.store.OutboxStore;
import com.aipersimmon.ddd.outbox.engine.store.PendingBacklog;
import com.aipersimmon.ddd.outbox.engine.store.PendingMessage;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The relay hands a claimed batch to the transport before waiting on any of it, and records what
 * came back in one write. These are the properties that has to leave untouched: an aggregate's
 * events are still never in flight together, one row's failure is still that row's alone, and a
 * message is still only recorded as sent once its own delivery was confirmed.
 */
@SpringBootTest(
    classes = OutboxRelayPipelineTest.TestApp.class,
    properties = {
      "aipersimmon.ddd.outbox.relay.enabled=false",
      "aipersimmon.ddd.outbox.retry.base-backoff-ms=0",
      "aipersimmon.ddd.outbox.retry.max-backoff-ms=0"
    })
class OutboxRelayPipelineTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {

    @Bean
    PipelinedDispatcher pipelinedDispatcher() {
      return new PipelinedDispatcher();
    }
  }

  /**
   * A transport that takes a message now and is acknowledged later, recording both moments on one
   * timeline — so a test can see the hand-over and the wait as separate events rather than
   * inferring the order from counts.
   */
  static final class PipelinedDispatcher implements OutboxDispatcher {
    final List<String> timeline = new CopyOnWriteArrayList<>();
    final Set<String> failing = ConcurrentHashMap.newKeySet();

    @Override
    public void dispatch(OutboxMessage message) {
      beginDispatch(message).awaitDelivery();
    }

    @Override
    public InFlightDispatch beginDispatch(OutboxMessage message) {
      String eventId = message.eventId();
      timeline.add("sent " + eventId);
      return () -> {
        timeline.add("acked " + eventId);
        if (failing.contains(eventId)) {
          throw new IllegalStateException("scripted failure for " + eventId);
        }
      };
    }
  }

  /** Counts the writes the relay makes, and can be told to fail the one that records a batch. */
  static final class CountingStore implements OutboxStore {
    private final OutboxStore delegate;
    final AtomicInteger markSentCalls = new AtomicInteger();
    boolean markSentFails;

    CountingStore(OutboxStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public void markSent(List<String> eventIds, Instant sentAt) {
      markSentCalls.incrementAndGet();
      if (markSentFails) {
        throw new IllegalStateException("the database went away between the ack and the write");
      }
      delegate.markSent(eventIds, sentAt);
    }

    @Override
    public void insert(OutboxInsert row) {
      delegate.insert(row);
    }

    @Override
    public List<PendingMessage> claimDue(
        Instant now, int maxAttempts, int batchSize, OutboxLease lease) {
      return delegate.claimDue(now, maxAttempts, batchSize, lease);
    }

    @Override
    public void release(List<String> eventIds) {
      delegate.release(eventIds);
    }

    @Override
    public void scheduleRetry(String eventId, Instant nextAttemptAt) {
      delegate.scheduleRetry(eventId, nextAttemptAt);
    }

    @Override
    public void backOffWithoutAttempt(String eventId, Instant nextAttemptAt) {
      delegate.backOffWithoutAttempt(eventId, nextAttemptAt);
    }

    @Override
    public int deleteSentBefore(Instant sentBefore, int limit) {
      return delegate.deleteSentBefore(sentBefore, limit);
    }

    @Override
    public PendingBacklog pendingBacklog(int maxAttempts) {
      return delegate.pendingBacklog(maxAttempts);
    }
  }

  /** Counts what the relay reported, so the duplicate cost of a failed batch write is visible. */
  static final class CountingObserver implements OutboxObserver {
    final AtomicInteger markSentFailedRows = new AtomicInteger();

    @Override
    public void claimed(int rows, Duration latency) {}

    @Override
    public void dispatched(boolean success, Duration latency) {}

    @Override
    public void deadLettered(DeadLetterStore.Reason reason) {}

    @Override
    public void markSentFailed(int rows) {
      markSentFailedRows.addAndGet(rows);
    }

    @Override
    public void released(int rows) {}
  }

  @Autowired OutboxStore store;
  @Autowired OutboxRelay relay;
  @Autowired DeadLetterStore deadLetterStore;
  @Autowired FailureClassifier failureClassifier;
  @Autowired PipelinedDispatcher dispatcher;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM aipersimmon_outbox");
    jdbc.update("DELETE FROM aipersimmon_dead_letter");
    dispatcher.timeline.clear();
    dispatcher.failing.clear();
  }

  @Test
  void theWholeBatchIsWithTheTransportBeforeAnyOfItIsWaitedOn() {
    insert("e1", null, 0);
    insert("e2", null, 1);
    insert("e3", null, 2);

    relay.relay();

    assertEquals(
        List.of("sent e1", "sent e2", "sent e3", "acked e1", "acked e2", "acked e3"),
        dispatcher.timeline,
        "waiting on each send before writing the next one made a poll cost the sum of its round "
            + "trips; overlapping them makes it cost about one");
    assertEquals(3, sentCount(), "and every one of them is still recorded as delivered");
  }

  @Test
  void twoEventsOfOneAggregateAreNeverInFlightTogether() {
    insert("e1", "agg-1", 0);
    insert("e2", "agg-1", 10);

    relay.relay();

    assertEquals(
        List.of("sent e1", "acked e1", "sent e2", "acked e2"),
        dispatcher.timeline,
        "only the head of an aggregate is claimable, so overlapping a batch's sends can never "
            + "overlap two events that must stay in order — the next one is not even claimed "
            + "until this one is delivered");
  }

  @Test
  void oneRowsFailureInAnOverlappedBatchIsStillThatRowsAlone() {
    insert("e1", null, 0);
    insert("e2", null, 1);
    insert("e3", null, 2);
    dispatcher.failing.add("e2");

    relay.relay();

    assertTrue(isSent("e1"), "a sibling's failure must not unsend what the broker already took");
    assertTrue(isSent("e3"));
    assertFalse(isSent("e2"), "a failed row is not recorded as delivered");
    assertEquals(
        1,
        attemptsOf("e2"),
        "the failed row alone counts an attempt and is left to be retried on the next poll");
  }

  @Test
  void aConfirmedBatchIsRecordedInOneWriteRatherThanOnePerRow() {
    insert("e1", null, 0);
    insert("e2", null, 1);
    insert("e3", null, 2);
    CountingStore counting = new CountingStore(store);

    relayOver(counting, new CountingObserver()).relay();

    assertEquals(3, sentCount(), "all three are recorded");
    assertEquals(
        1,
        counting.markSentCalls.get(),
        "one write for the batch: once the sends overlap, a round trip per row is what is left "
            + "to become the bottleneck");
  }

  @Test
  void aFailureToRecordAConfirmedBatchLeavesEveryRowToBeDeliveredAgain() {
    insert("e1", null, 0);
    insert("e2", null, 1);
    CountingStore counting = new CountingStore(store);
    counting.markSentFails = true;
    CountingObserver observer = new CountingObserver();

    relayOver(counting, observer).relay();

    // The broker has the messages; failing to write that down is not a delivery failure. Never
    // count it against the retry budget — that would dead-letter messages that were delivered.
    assertEquals(0, attemptsOf("e1"), "no attempt is charged for a bookkeeping failure");
    assertEquals(0, attemptsOf("e2"));
    assertNull(leaseTokenOf("e1"), "the rows are handed back rather than waiting out their lease");
    assertNull(leaseTokenOf("e2"));
    assertEquals(
        2,
        observer.markSentFailedRows.get(),
        "the count reads as how many duplicates follow, which is what a batch write costs when "
            + "it fails");
  }

  private OutboxRelay relayOver(OutboxStore over, OutboxObserver observer) {
    return new OutboxRelay(
        over,
        dispatcher,
        deadLetterStore,
        failureClassifier,
        new RetryBackoff(0, 0),
        Clock.systemUTC(),
        10,
        10,
        RelayLeases.ownedBy("node-A", Duration.ofMinutes(5)),
        NoOpStoreAndForwardTracer.INSTANCE,
        observer);
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

  private int sentCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM aipersimmon_outbox WHERE sent = TRUE", Integer.class);
  }

  private boolean isSent(String eventId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT sent FROM aipersimmon_outbox WHERE event_id = ?", Boolean.class, eventId));
  }

  private int attemptsOf(String eventId) {
    return jdbc.queryForObject(
        "SELECT attempts FROM aipersimmon_outbox WHERE event_id = ?", Integer.class, eventId);
  }

  private String leaseTokenOf(String eventId) {
    return jdbc.queryForObject(
        "SELECT lease_token FROM aipersimmon_outbox WHERE event_id = ?", String.class, eventId);
  }
}
