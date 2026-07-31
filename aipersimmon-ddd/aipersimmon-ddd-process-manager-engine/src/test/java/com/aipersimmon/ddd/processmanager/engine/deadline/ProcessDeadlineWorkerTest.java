package com.aipersimmon.ddd.processmanager.engine.deadline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.engine.lease.ProcessClaimStrategy;
import com.aipersimmon.ddd.processmanager.engine.retry.ExponentialBackoffPolicy;
import com.aipersimmon.ddd.processmanager.engine.store.DeadlineStatus;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.engine.store.RollingBackUnitOfWork;
import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.runtime.ProcessAdvanceResult;
import com.aipersimmon.ddd.processmanager.runtime.ProcessRuntime;
import com.aipersimmon.ddd.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * When a timer fires, and — more interesting — when it must not.
 *
 * <p>A timer that was rescheduled, cancelled, or whose instance ended between the claim and the
 * fire has to become an auditable no-op rather than an event, because the advance it would trigger
 * is indistinguishable from a real timeout once it has happened. The generation is what makes
 * "rescheduled" recognisable; the status re-read under lock is what makes "cancelled" recognisable;
 * and marking FIRED <em>before</em> the advance rather than after is what keeps a terminal decision
 * from rewriting a timer that did fire as one that was cancelled.
 */
class ProcessDeadlineWorkerTest {

  private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");
  private static final ProcessInstanceId INSTANCE = new ProcessInstanceId("instance-1");
  private static final DeadlineName TIMEOUT = new DeadlineName("payment-timeout");
  private static final PayloadType PAYLOAD = new PayloadType("sample.payload", 1);

  private final InMemoryProcessDeadlineStore deadlines = new InMemoryProcessDeadlineStore();
  private final InMemoryProcessInstanceStore instances = new InMemoryProcessInstanceStore();
  private final RecordingRuntime runtime = new RecordingRuntime();
  private final SteppingClock clock = new SteppingClock(NOW);

  /** A clock a test moves by hand, so a retry's backoff can actually come due. */
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
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }

  private record TimedOut(String reason) implements ProcessInput {}

  /** Records the advances a timer triggered, plus the tenant bound while it ran. */
  private final class RecordingRuntime implements ProcessRuntime {
    private final List<String> advances = new ArrayList<>();
    private final List<String> tenantsSeen = new ArrayList<>();
    private boolean failing;
    private Runnable duringAdvance = () -> {};

    @Override
    public ProcessAdvanceResult start(
        ProcessType processType,
        ProcessBusinessKey businessKey,
        ProcessInput input,
        CommandContext cause) {
      throw new UnsupportedOperationException("a timer never starts an instance");
    }

    @Override
    public ProcessAdvanceResult handle(
        ProcessType processType,
        ProcessBusinessKey businessKey,
        ProcessInput input,
        CommandContext cause) {
      throw new UnsupportedOperationException("a timer addresses its instance by ref");
    }

    @Override
    public ProcessAdvanceResult handle(
        ProcessRef processRef, ProcessInput input, CommandContext cause) {
      advances.add(cause.messageId());
      tenantsSeen.add(TenantContext.current().map(tenant -> tenant.value()).orElse("<unbound>"));
      duringAdvance.run();
      if (failing) {
        throw new IllegalStateException("the advance blew up");
      }
      return null;
    }
  }

  private static final class PassThroughCodec implements ProcessPayloadCodec<TimedOut> {
    @Override
    public PayloadType payloadType() {
      return PAYLOAD;
    }

    @Override
    public Class<TimedOut> javaType() {
      return TimedOut.class;
    }

    @Override
    public EncodedPayload encode(TimedOut value) {
      return new EncodedPayload(PAYLOAD, value.reason().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public TimedOut decode(EncodedPayload payload) {
      return new TimedOut(new String(payload.data(), StandardCharsets.UTF_8));
    }
  }

  private ProcessDeadlineWorker worker() {
    return worker(() -> "lease-A", 3);
  }

  private ProcessDeadlineWorker worker(Supplier<String> leaseTokens, int maxAttempts) {
    ProcessClaimStrategy claim =
        new ProcessClaimStrategy() {
          @Override
          public String id() {
            return "in-memory";
          }

          @Override
          public List<String> claimDueEffects(
              Instant now, int limit, String leaseToken, Instant leaseUntil) {
            return List.of();
          }

          @Override
          public List<String> claimDueDeadlines(
              Instant now, int limit, String leaseToken, Instant leaseUntil) {
            return deadlines.claimDue(now, limit, leaseToken, leaseUntil);
          }
        };
    return new ProcessDeadlineWorker(
        claim,
        deadlines,
        instances,
        new ProcessPayloadCodecRegistry(List.of(new PassThroughCodec())),
        runtime,
        new RollingBackUnitOfWork(deadlines, instances),
        new ExponentialBackoffPolicy(
            Duration.ofMillis(100), Duration.ofSeconds(10), 2.0, 0.0, maxAttempts, () -> 0.5),
        clock,
        10,
        Duration.ofMinutes(5),
        leaseTokens);
  }

  private void arm(String deadlineId) {
    deadlines.schedule(deadlines.arm(deadlineId, INSTANCE, TIMEOUT, NOW.minusSeconds(1)), NOW);
  }

  @Test
  void aDueTimerFiresAnAdvanceAndIsRecordedFired() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    arm("deadline-1");

    assertEquals(1, worker().pollOnce());

    assertEquals(List.of("deadline-1#1"), runtime.advances, "the timer's own id and generation");
    assertEquals(DeadlineStatus.FIRED, deadlines.row("deadline-1").status());
  }

  @Test
  void aTimerThatWasRescheduledDoesNotAlsoFireItsOldGeneration() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    arm("deadline-1");
    // Rescheduling the same named timer mints generation 2; the row for generation 1 is still
    // sitting there, due, and would fire if nothing recognised it as stale.
    arm("deadline-2");

    worker().pollOnce();

    assertEquals(
        List.of("deadline-2#2"),
        runtime.advances,
        "only the current generation fires — a timeout that was pushed back must not also arrive "
            + "at the moment it was originally set for");
    assertEquals(DeadlineStatus.CANCELLED, deadlines.row("deadline-1").status());
  }

  @Test
  void aTimerCancelledBetweenTheClaimAndTheFireIsANoOp() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    arm("deadline-1");
    // Claim it, then let a cancel land — which is what the status re-read under lock is for.
    ProcessDeadlineWorker cancelling = workerCancellingDuringClaim();

    assertEquals(0, cancelling.pollOnce());

    assertEquals(List.of(), runtime.advances);
  }

  @Test
  void aTimerWhoseInstanceHasVanishedIsRetiredRatherThanFired() {
    arm("deadline-1");

    assertEquals(0, worker().pollOnce());

    assertEquals(List.of(), runtime.advances);
    assertEquals(DeadlineStatus.CANCELLED, deadlines.row("deadline-1").status());
  }

  @Test
  void aTimerIsMarkedFiredBeforeTheAdvanceSoATerminalDecisionCannotRewriteIt() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    arm("deadline-1");
    // The advance this timer triggers ends the process, and a terminal decision cancels every live
    // deadline. If the mark came after, this timer — which did fire — would be recorded CANCELLED.
    runtime.duringAdvance = () -> deadlines.cancelLive(INSTANCE, NOW);

    worker().pollOnce();

    assertEquals(
        DeadlineStatus.FIRED,
        deadlines.row("deadline-1").status(),
        "a timer that fired is recorded as having fired, whatever the advance went on to do");
  }

  @Test
  void theOwningTenantIsBoundAroundTheFireEvenThoughTheWorkerThreadHasNone() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    arm("deadline-1");

    worker().pollOnce();

    assertEquals(List.of("acme"), runtime.tenantsSeen);
  }

  @Test
  void aFailedFireSpendsOneAttemptAndComesBackLater() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    arm("deadline-1");
    runtime.failing = true;

    assertEquals(0, worker().pollOnce());

    assertEquals(DeadlineStatus.PENDING, deadlines.row("deadline-1").status());
    assertEquals(1, deadlines.row("deadline-1").attempts());
    assertTrue(deadlines.row("deadline-1").lastError().contains("the advance blew up"));
  }

  @Test
  void aTimerIsGivenUpOnOnlyOnceItsBudgetIsGoneAndItSuspendsTheInstance() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    arm("deadline-1");
    runtime.failing = true;

    for (int attempt = 1; attempt <= 3; attempt++) {
      String token = "lease-" + attempt;
      worker(() -> token, 3).pollOnce();
      clock.advance(Duration.ofHours(1)); // past whatever backoff the retry scheduled
    }

    assertEquals(DeadlineStatus.DEAD, deadlines.row("deadline-1").status());
    assertEquals(ProcessLifecycle.SUSPENDED, instances.row(INSTANCE).lifecycle());
    assertEquals("DEADLINE", instances.suspensionSourceOf(INSTANCE));
  }

  @Test
  void aFailedFireIsRetryableOnlyBecauseTheFiredMarkIsRolledBackWithIt() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    arm("deadline-1");
    runtime.failing = true;

    worker().pollOnce();

    // Worth pinning because it is invisible in either file on its own. The worker marks the timer
    // FIRED and clears its lease before running the advance; its retry path then needs the row back
    // in IN_FLIGHT under that same lease (the real UPDATE says `WHERE lease_token = ? AND status =
    // 'IN_FLIGHT'`). Both only line up because the throw rolls the mark back. Make the unit of work
    // a pass-through and this row stays FIRED forever: never fired for real, never retried, never
    // dead-lettered, and nothing anywhere says so.
    assertEquals(DeadlineStatus.PENDING, deadlines.row("deadline-1").status());
    assertEquals(1, deadlines.row("deadline-1").attempts());
  }

  @Test
  void aTimerThatExhaustsItsRetriesOnAnAlreadyEndedInstanceDoesNotTryToSuspendIt() {
    instances.given(INSTANCE, ProcessLifecycle.COMPLETED);
    arm("deadline-1");
    runtime.failing = true;

    for (int attempt = 1; attempt <= 3; attempt++) {
      String token = "lease-" + attempt;
      worker(() -> token, 3).pollOnce();
      clock.advance(Duration.ofHours(1));
    }

    assertEquals(DeadlineStatus.DEAD, deadlines.row("deadline-1").status());
    assertEquals(
        ProcessLifecycle.COMPLETED,
        instances.row(INSTANCE).lifecycle(),
        "a finished process has nothing to suspend; rewriting a terminal lifecycle would be worse "
            + "than the dead timer it is reacting to");
  }

  @Test
  void aWorkerWhoseLeaseExpiredCannotSettleATimerSomebodyElseTookOver() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    arm("deadline-1");
    deadlines.claimDue(NOW, 10, "lease-A", NOW.plusSeconds(60));
    deadlines.expireLease("deadline-1");

    worker(() -> "lease-B", 3).pollOnce();

    assertEquals(0, deadlines.markFired("deadline-1", "lease-A", NOW));
    assertEquals(0, deadlines.markDead("deadline-1", "lease-A", "stale", NOW));
    assertEquals(DeadlineStatus.FIRED, deadlines.row("deadline-1").status());
  }

  /** Claims the timer and then cancels it, standing in for a cancel that lands mid-flight. */
  private ProcessDeadlineWorker workerCancellingDuringClaim() {
    ProcessClaimStrategy claim =
        new ProcessClaimStrategy() {
          @Override
          public String id() {
            return "in-memory";
          }

          @Override
          public List<String> claimDueEffects(
              Instant now, int limit, String leaseToken, Instant leaseUntil) {
            return List.of();
          }

          @Override
          public List<String> claimDueDeadlines(
              Instant now, int limit, String leaseToken, Instant leaseUntil) {
            List<String> claimed = deadlines.claimDue(now, limit, leaseToken, leaseUntil);
            deadlines.cancelLive(INSTANCE, now);
            return claimed;
          }
        };
    return new ProcessDeadlineWorker(
        claim,
        deadlines,
        instances,
        new ProcessPayloadCodecRegistry(List.of(new PassThroughCodec())),
        runtime,
        new RollingBackUnitOfWork(deadlines, instances),
        new ExponentialBackoffPolicy(
            Duration.ofMillis(100), Duration.ofSeconds(10), 2.0, 0.0, 3, () -> 0.5),
        clock,
        10,
        Duration.ofMinutes(5),
        () -> "lease-A");
  }
}
