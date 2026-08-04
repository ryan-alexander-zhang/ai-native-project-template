package com.aipersimmon.ddd.outbox.engine.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.integration.MalformedIntegrationEventException;
import com.aipersimmon.ddd.observability.NoOpStoreAndForwardTracer;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.DefaultFailureClassifier;
import com.aipersimmon.ddd.outbox.InFlightDispatch;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.aipersimmon.ddd.outbox.RetryBackoff;
import com.aipersimmon.ddd.outbox.UnreachableDestinationException;
import com.aipersimmon.ddd.outbox.engine.observe.OutboxObserver;
import com.aipersimmon.ddd.outbox.engine.store.InMemoryOutboxStore;
import com.aipersimmon.ddd.outbox.engine.store.OutboxInsert;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The relay's judgements, tested where they live.
 *
 * <p>Every one of these was previously only reachable through a storage backend, so the module that
 * owns the decision had no test of its own and the same reasoning was covered twice — once per
 * backend — or, for the parts both backends happened not to exercise, not at all. What is asserted
 * here is deliberately the storage-independent half: the retry budget, what a failure to record a
 * delivery means, what stops a poll, and what happens when giving up itself fails. Whether a given
 * SQL dialect implements the claim correctly stays with the backends, against a real database.
 */
class OutboxRelayTest {

  /** A transport a test can script: which ids fail, and how, in what order it was called. */
  private static final class ScriptedDispatcher implements OutboxDispatcher {
    private final List<String> timeline = new ArrayList<>();
    private final Set<String> failing = new HashSet<>();
    private final Set<String> failingPermanently = new HashSet<>();

    /** Fails the way a real transport does: a content-free wrapper over the actual cause. */
    private final Set<String> failingThroughAWrapper = new HashSet<>();

    private boolean reachesExternalTargets = true;

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
        if (failingPermanently.contains(eventId)) {
          // One of the three the default classifier calls permanent: no number of retries
          // supplies an attribute the message never carried.
          throw new MalformedIntegrationEventException("permanently broken " + eventId);
        }
        if (failingThroughAWrapper.contains(eventId)) {
          throw new IllegalStateException(
              "Send failed",
              new IllegalArgumentException(
                  "Topic orders.events not present in metadata after 5000 ms"));
        }
        if (failing.contains(eventId)) {
          throw new IllegalStateException("transiently broken " + eventId);
        }
      };
    }

    @Override
    public boolean reachesExternalTargets() {
      return reachesExternalTargets;
    }
  }

  /** A dead-letter store that records the moves, and can be told to be unavailable. */
  private static final class RecordingDeadLetters implements DeadLetterStore {
    private final List<String> stored = new ArrayList<>();
    private final List<Reason> reasons = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final InMemoryOutboxStore outbox;
    private boolean unavailable;

    RecordingDeadLetters(InMemoryOutboxStore outbox) {
      this.outbox = outbox;
    }

    @Override
    public void store(
        OutboxMessage message,
        int attempts,
        Reason reason,
        String lastError,
        String traceparent,
        String traceState) {
      if (unavailable) {
        throw new IllegalStateException("the dead-letter store is unavailable");
      }
      stored.add(message.eventId());
      reasons.add(reason);
      errors.add(lastError);
      // A real store moves the row out of the outbox in the same transaction.
      outbox.remove(message.eventId());
    }

    @Override
    public boolean replay(String eventId) {
      return false;
    }
  }

  /** A clock a test moves by hand, so a poll's time budget can be made to run out. */
  private static final class SteppingClock extends Clock {
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

  /** Counts what the relay reported, so the report is asserted rather than assumed. */
  private static final class CountingObserver implements OutboxObserver {
    private final List<Integer> claims = new ArrayList<>();
    private final List<Boolean> dispatches = new ArrayList<>();
    private final List<DeadLetterStore.Reason> deadLetters = new ArrayList<>();
    private int markSentFailedRows;
    private int releasedRows;

    @Override
    public void claimed(int rows, Duration latency) {
      claims.add(rows);
    }

    @Override
    public void dispatched(boolean success, Duration latency) {
      dispatches.add(success);
    }

    @Override
    public void deadLettered(DeadLetterStore.Reason reason) {
      deadLetters.add(reason);
    }

    @Override
    public void markSentFailed(int rows) {
      markSentFailedRows += rows;
    }

    @Override
    public void released(int rows) {
      releasedRows += rows;
    }
  }

  /** Records when each span became current, left the thread, failed and ended. */
  private static final class RecordingTracer implements StoreAndForwardTracer {
    private final List<String> timeline = new ArrayList<>();

    @Override
    public Captured captureCurrent() {
      return Captured.NONE;
    }

    @Override
    public Scope restore(String traceparent, String traceState, String spanName) {
      String eventId = spanName.substring(spanName.lastIndexOf(' ') + 1);
      timeline.add("restored " + eventId);
      return new Scope() {
        @Override
        public void recordFailure(Throwable error) {
          timeline.add("failed " + eventId);
        }

        @Override
        public void detach() {
          timeline.add("detached " + eventId);
        }

        @Override
        public void close() {
          timeline.add("closed " + eventId);
        }
      };
    }
  }

  private static final Instant START = Instant.parse("2026-07-30T10:00:00Z");
  private static final int MAX_ATTEMPTS = 3;

  private final InMemoryOutboxStore store = new InMemoryOutboxStore();
  private final RecordingDeadLetters deadLetters = new RecordingDeadLetters(store);
  private final ScriptedDispatcher dispatcher = new ScriptedDispatcher();
  private final CountingObserver observer = new CountingObserver();
  private SteppingClock clock;

  @BeforeEach
  void reset() {
    clock = new SteppingClock(START);
  }

  private OutboxRelay relay() {
    return relayLeasedFor(Duration.ofMinutes(10));
  }

  private OutboxRelay relayLeasedFor(Duration lease) {
    return new OutboxRelay(
        store,
        dispatcher,
        deadLetters,
        new DefaultFailureClassifier(),
        new RetryBackoff(1000, 60_000),
        clock,
        10,
        MAX_ATTEMPTS,
        RelayLeases.ownedBy("node-A", lease),
        NoOpStoreAndForwardTracer.INSTANCE,
        observer);
  }

  private OutboxRelay relayOver(OutboxDispatcher transport) {
    return new OutboxRelay(
        store,
        transport,
        deadLetters,
        new DefaultFailureClassifier(),
        new RetryBackoff(1000, 60_000),
        clock,
        10,
        MAX_ATTEMPTS,
        RelayLeases.ownedBy("node-A", Duration.ofMinutes(10)),
        NoOpStoreAndForwardTracer.INSTANCE,
        observer);
  }

  private List<String> deliveredIds() {
    return store.eventIds().stream().filter(store::isSent).toList();
  }

  private void insert(String eventId, String subject) {
    insert(eventId, subject, null);
  }

  private void insert(String eventId, String subject, String destination) {
    store.insert(
        new OutboxInsert(
            eventId,
            "/test",
            "SampleEvent",
            1,
            "{}",
            START,
            subject,
            "acme",
            "corr-1",
            null,
            destination,
            null,
            null,
            START.plusMillis(store.eventIds().size())));
  }

  @Test
  void aDeliveredRowIsRecordedSentAndReported() {
    insert("e1", null);

    relay().relay();

    assertTrue(store.isSent("e1"));
    assertEquals(List.of(true), observer.dispatches);
    assertNull(
        store.leaseTokenOf("e1"), "and it no longer holds the claim it was dispatched under");
  }

  @Test
  void aTransientFailureSpendsOneAttemptAndIsPushedOutRatherThanRetriedAtOnce() {
    insert("e1", null);
    dispatcher.failing.add("e1");

    relay().relay();

    assertFalse(store.isSent("e1"));
    assertEquals(1, store.attemptsOf("e1"), "one attempt, not the whole budget");
    Instant nextAttempt = store.nextAttemptAtOf("e1");
    assertTrue(
        nextAttempt.isAfter(START) && !nextAttempt.isAfter(START.plusMillis(1000)),
        "and the next attempt is pushed out by the backoff — somewhere inside the first cap, the "
            + "exact point being jittered so a fleet does not retry in lockstep — so the claim "
            + "skips it until then, rather than re-attempting it every second. Was: "
            + nextAttempt);
    assertEquals(List.of(), deadLetters.stored, "a transient failure is not given up on yet");
  }

  @Test
  void aPermanentFailureIsGivenUpOnAtOnceWithoutSpendingRetries() {
    insert("e1", null);
    dispatcher.failingPermanently.add("e1");

    relay().relay();

    assertEquals(List.of("e1"), deadLetters.stored);
    assertEquals(List.of(DeadLetterStore.Reason.PERMANENT), deadLetters.reasons);
    assertEquals(
        List.of(DeadLetterStore.Reason.PERMANENT),
        observer.deadLetters,
        "no number of retries makes a malformed message deliverable, so none are spent");
  }

  @Test
  void aTransientFailureIsGivenUpOnOnlyAfterItsBudgetIsGone() {
    insert("e1", null);
    dispatcher.failing.add("e1");

    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      clock.advance(Duration.ofHours(1)); // past whatever backoff was scheduled
      relay().relay();
    }

    assertEquals(List.of("e1"), deadLetters.stored);
    assertEquals(List.of(DeadLetterStore.Reason.RETRIES_EXHAUSTED), deadLetters.reasons);
  }

  /**
   * What is recorded about the give-up is the whole cause chain, not the outermost frame.
   *
   * <p>The outermost frame of a real transport failure carries nothing an operator can act on —
   * Spring Kafka's synchronous send failure is {@code KafkaException: Send failed}, and the topic
   * name and the reason are underneath it. {@code last_error} is the only column in a dead letter
   * that answers "where was this going and why did it not get there", so recording only the wrapper
   * turned it into a restatement of the row's own existence. issue-00165.
   */
  @Test
  void thegiveUpRecordsTheCauseChainAndNotJustTheWrapper() {
    insert("e1", null);
    dispatcher.failingThroughAWrapper.add("e1");

    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      clock.advance(Duration.ofHours(1));
      relay().relay();
    }

    assertEquals(1, deadLetters.errors.size());
    String recorded = deadLetters.errors.get(0);
    assertTrue(recorded.contains("Send failed"), recorded);
    assertTrue(
        recorded.contains("Topic orders.events not present in metadata"),
        "the cause is the half an operator needs: " + recorded);
  }

  @Test
  void recordingADeliveryIsNotADeliveryFailureSoItNeverCostsTheRetryBudget() {
    insert("e1", null);
    store.failOn("markSent");

    relay().relay();

    // The transport already has the message. Charging an attempt (let alone dead-lettering) would
    // discard or misreport something that was in fact delivered.
    assertEquals(0, store.attemptsOf("e1"), "no attempt is charged for a bookkeeping failure");
    assertEquals(List.of(), deadLetters.stored);
    assertEquals(1, observer.markSentFailedRows);
    assertNull(
        store.leaseTokenOf("e1"),
        "the row is handed back so the next poll re-delivers it, rather than waiting out a lease");
  }

  @Test
  void givingUpOnARowWhoseDeadLetterStoreIsDownBacksItOffWithoutSpendingAnAttempt() {
    insert("e1", null);
    dispatcher.failingPermanently.add("e1");
    deadLetters.unavailable = true;

    relay().relay();

    // Leaving attempts alone is what keeps the row claimable: it must retry the move until the
    // store recovers, rather than cross max-attempts and be stranded with nothing looking at it.
    assertEquals(
        0, store.attemptsOf("e1"), "the move failed; the delivery attempt is not the story");
    assertTrue(
        store.nextAttemptAtOf("e1").isAfter(START),
        "but it is spaced out, so it is not re-attempted every single poll");
    assertEquals(
        List.of(), observer.deadLetters, "nothing was given up on — the move did not happen");
  }

  @Test
  void aPollKeepsClaimingSoOneBusyAggregateStillDrainsInOrder() {
    insert("e1", "agg-1");
    insert("e2", "agg-1");
    insert("e3", "agg-1");

    relay().relay();

    assertEquals(
        List.of("sent e1", "acked e1", "sent e2", "acked e2", "sent e3", "acked e3"),
        dispatcher.timeline,
        "only the head of an aggregate is claimable, so a poll claims again as each row is "
            + "delivered — and an aggregate's events are never in flight together");
    assertEquals(
        List.of(1, 1, 1, 0), observer.claims, "four claims: three rows, then nothing left");
  }

  @Test
  void aRowLeftImmediatelyClaimableEndsThePollRatherThanBeingRetriedInATightLoop() {
    OutboxRelay noBackoff =
        new OutboxRelay(
            store,
            dispatcher,
            deadLetters,
            new DefaultFailureClassifier(),
            new RetryBackoff(0, 0),
            clock,
            10,
            MAX_ATTEMPTS,
            RelayLeases.ownedBy("node-A", Duration.ofMinutes(10)));
    insert("e1", null);
    dispatcher.failing.add("e1");

    noBackoff.relay();

    // With zero backoff the row is claimable again the instant it is released. Claiming again in
    // the same poll would burn its whole attempt budget in one tight loop.
    assertEquals(1, store.attemptsOf("e1"), "exactly one attempt was spent, not three");
  }

  @Test
  void givingUpOnARowStillFreesItsAggregateWithinTheSamePoll() {
    insert("e1", "agg-1");
    insert("e2", "agg-1");
    dispatcher.failingPermanently.add("e1");

    relay().relay();

    // A dead-lettered row has left the table, so it blocks nothing — unlike one that is merely
    // backing off. Ordering holds right up to the point a message is given up on.
    assertEquals(List.of("e1"), deadLetters.stored);
    assertTrue(store.isSent("e2"), "its successor became the head and went out in the same poll");
  }

  @Test
  void aPollThatSpendsItsTimeBudgetHandsBackWhatItNeverReached() {
    insert("e1", null);
    insert("e2", null);
    insert("e3", null);
    // A two-second lease gives the poll one second of budget; one slow hand-over overruns it.
    OutboxDispatcher slow = message -> clock.advance(Duration.ofSeconds(30));
    OutboxRelay bounded =
        new OutboxRelay(
            store,
            slow,
            deadLetters,
            new DefaultFailureClassifier(),
            new RetryBackoff(0, 0),
            clock,
            10,
            MAX_ATTEMPTS,
            RelayLeases.ownedBy("node-A", Duration.ofSeconds(2)),
            NoOpStoreAndForwardTracer.INSTANCE,
            observer);

    bounded.relay();

    assertTrue(store.isSent("e1"), "the row it did reach is delivered");
    assertNull(
        store.leaseTokenOf("e2"), "the ones it never reached are handed back, not left leased");
    assertNull(store.leaseTokenOf("e3"));
    assertEquals(
        2,
        observer.releasedRows,
        "and that hand-back is reported, because it is the one signal that says the budget is too "
            + "small for how slow dispatch has become");
  }

  @Test
  void aRowBoundForAnExternalDestinationIsNeverHandedToATransportThatCannotReachOne() {
    insert("e1", null, "ordering.events");
    dispatcher.reachesExternalTargets = false;

    relay().relay();

    assertEquals(
        List.of(),
        dispatcher.timeline,
        "it is refused before the transport sees it: delivering it locally would archive it as "
            + "sent while it never left the process");
    assertFalse(store.isSent("e1"));
    assertEquals(
        1,
        store.attemptsOf("e1"),
        "and it is treated as transient — a missing transport "
            + "is usually a window during a rolling release, not a verdict");
  }

  @Test
  void anUnreachableDestinationEventuallyBecomesAVisibleDeadLetter() {
    insert("e1", null, "ordering.events");
    dispatcher.reachesExternalTargets = false;

    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      clock.advance(Duration.ofHours(1));
      relay().relay();
    }

    assertEquals(List.of("e1"), deadLetters.stored);
    assertEquals(List.of(DeadLetterStore.Reason.RETRIES_EXHAUSTED), deadLetters.reasons);
  }

  @Test
  void anUnreachableDestinationNamesTheEventAndWhereItWasGoing() {
    UnreachableDestinationException error =
        new UnreachableDestinationException("SampleEvent", 1, "ordering.events");

    assertTrue(error.getMessage().contains("SampleEvent"));
    assertTrue(error.getMessage().contains("ordering.events"));
  }

  @Test
  void aTransportThatFailsBeforeItEvenTakesTheMessageIsTreatedLikeAnyOtherFailure() {
    insert("e1", null);
    OutboxDispatcher refusesToTakeIt =
        new OutboxDispatcher() {
          @Override
          public void dispatch(OutboxMessage message) {
            throw new IllegalStateException("the producer is closed");
          }
        };

    RecordingTracer tracer = new RecordingTracer();

    new OutboxRelay(
            store,
            refusesToTakeIt,
            deadLetters,
            new DefaultFailureClassifier(),
            new RetryBackoff(1000, 60_000),
            clock,
            10,
            MAX_ATTEMPTS,
            RelayLeases.ownedBy("node-A", Duration.ofMinutes(10)),
            tracer,
            observer)
        .relay();

    assertEquals(
        List.of("restored e1", "detached e1", "failed e1", "closed e1"),
        tracer.timeline,
        "the span still has to leave the thread even when hand-over threw, or it would stay "
            + "current under everything the poll does next");
    // Hand-over can fail on its own — a closed producer, a full buffer, metadata that never
    // arrives. It has to reach the same place as a failed acknowledgement, or one of the two
    // ways a dispatch can fail would quietly bypass the retry budget entirely.
    assertFalse(store.isSent("e1"));
    assertEquals(1, store.attemptsOf("e1"));
    assertEquals(List.of(false), observer.dispatches, "and it is reported as a failed dispatch");
  }

  @Test
  void theSpanLeavesTheThreadAtHandOverButOnlyEndsOnceDeliveryIsConfirmed() {
    insert("e1", null);
    insert("e2", null);
    RecordingTracer tracer = new RecordingTracer();

    new OutboxRelay(
            store,
            dispatcher,
            deadLetters,
            new DefaultFailureClassifier(),
            new RetryBackoff(0, 0),
            clock,
            10,
            MAX_ATTEMPTS,
            RelayLeases.ownedBy("node-A", Duration.ofMinutes(10)),
            tracer,
            observer)
        .relay();

    // The context must be current only while the transport reads it, or the second hand-over
    // would happen inside the first message's span; and the span must outlive that, or a failed
    // acknowledgement would have nowhere to be recorded.
    assertEquals(
        List.of(
            "restored e1", "detached e1", "restored e2", "detached e2", "closed e1", "closed e2"),
        tracer.timeline);
  }

  @Test
  void aFailedDeliveryIsRecordedOnTheSpanThatSentIt() {
    insert("e1", null);
    dispatcher.failing.add("e1");
    RecordingTracer tracer = new RecordingTracer();

    new OutboxRelay(
            store,
            dispatcher,
            deadLetters,
            new DefaultFailureClassifier(),
            new RetryBackoff(0, 0),
            clock,
            10,
            MAX_ATTEMPTS,
            RelayLeases.ownedBy("node-A", Duration.ofMinutes(10)),
            tracer,
            observer)
        .relay();

    assertTrue(
        tracer.timeline.contains("failed e1"),
        "otherwise a dispatch that failed shows up in the trace as one that succeeded");
  }

  @Test
  void aPollDeliversAtMostOneBatchAndLeavesTheRestForTheNextOne() {
    for (int i = 1; i <= 5; i++) {
      insert("e" + i, null);
    }
    OutboxRelay twoAtATime =
        new OutboxRelay(
            store,
            dispatcher,
            deadLetters,
            new DefaultFailureClassifier(),
            new RetryBackoff(0, 0),
            clock,
            2,
            MAX_ATTEMPTS,
            RelayLeases.ownedBy("node-A", Duration.ofMinutes(10)));

    twoAtATime.relay();

    // Every instance polls, so this bounds one poll's work rather than the deployment's
    // throughput — and a poll that ignored it would hold a claim on the whole table.
    assertEquals(List.of("e1", "e2"), deliveredIds());
    assertFalse(store.isSent("e3"));
  }

  @Test
  void aDeadLetterRecordsWhatTheFailureActuallyWas() {
    insert("e1", null);
    dispatcher.failingPermanently.add("e1");

    relay().relay();

    assertEquals(
        List.of(MalformedIntegrationEventException.class.getName() + ": permanently broken e1"),
        deadLetters.errors,
        "a dead letter nobody can diagnose is only marginally better than a lost message");
  }

  @Test
  void aClaimThatFindsNothingReportsAnEmptyClaimAndStops() {
    relay().relay();

    assertEquals(List.of(0), observer.claims);
    assertEquals(List.of(), dispatcher.timeline);
  }
}
