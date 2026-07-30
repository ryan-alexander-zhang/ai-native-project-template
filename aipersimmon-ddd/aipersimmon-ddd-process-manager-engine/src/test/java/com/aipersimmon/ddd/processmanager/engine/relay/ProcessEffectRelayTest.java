package com.aipersimmon.ddd.processmanager.engine.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodec;
import com.aipersimmon.ddd.processmanager.codec.ProcessPayloadCodecRegistry;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;
import com.aipersimmon.ddd.processmanager.engine.lease.ProcessClaimStrategy;
import com.aipersimmon.ddd.processmanager.engine.retry.ExponentialBackoffPolicy;
import com.aipersimmon.ddd.processmanager.engine.store.EffectStatus;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessEffectStore;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.engine.store.RollingBackUnitOfWork;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * How a staged effect actually reaches the outside world, and the four things that must hold while
 * it does: an operator's cancel is honoured even for work already in flight, a worker that lost its
 * lease cannot complete an effect somebody else took over, the retry budget is spent by failures
 * and not by reclaims, and the owning tenant is bound around the dispatch even though the relay
 * thread has none.
 */
class ProcessEffectRelayTest {

  private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");
  private static final ProcessInstanceId INSTANCE = new ProcessInstanceId("instance-1");
  private static final PayloadType PAYLOAD = new PayloadType("sample.payload", 1);

  private final InMemoryProcessEffectStore effects = new InMemoryProcessEffectStore();
  private final InMemoryProcessInstanceStore instances = new InMemoryProcessInstanceStore();
  private final RecordingDispatcher dispatcher = new RecordingDispatcher();
  private final SteppingClock clock = new SteppingClock(NOW);

  /** Records what it was handed, the tenant bound at the time, and can be told to fail. */
  private static final class RecordingDispatcher implements ProcessEffectDispatcher {
    private final List<String> dispatched = new ArrayList<>();
    private final List<String> tenantsSeen = new ArrayList<>();
    private final Set<String> failing = new HashSet<>();

    @Override
    public ProcessEffectKind kind() {
      return ProcessEffectKind.DISPATCH_COMMAND;
    }

    @Override
    public void dispatch(DecodedProcessEffect effect, CommandContext context) {
      dispatched.add(effect.effectId());
      tenantsSeen.add(TenantContext.current().map(tenant -> tenant.value()).orElse("<unbound>"));
      if (failing.contains(effect.effectId())) {
        throw new IllegalStateException("downstream is down for " + effect.effectId());
      }
    }
  }

  /** A codec that only has to round-trip; what it decodes to is irrelevant here. */
  private static final class PassThroughCodec implements ProcessPayloadCodec<String> {
    @Override
    public PayloadType payloadType() {
      return PAYLOAD;
    }

    @Override
    public Class<String> javaType() {
      return String.class;
    }

    @Override
    public EncodedPayload encode(String value) {
      return new EncodedPayload(PAYLOAD, value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String decode(EncodedPayload payload) {
      return new String(payload.data(), StandardCharsets.UTF_8);
    }
  }

  /** No transaction manager here; the engine's decisions are what is under test. */
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

  private ProcessEffectRelay relay(Supplier<String> leaseTokens, int maxAttempts) {
    ProcessClaimStrategy claim =
        new ProcessClaimStrategy() {
          @Override
          public String id() {
            return "in-memory";
          }

          @Override
          public List<String> claimDueEffects(
              Instant now, int limit, String leaseToken, Instant leaseUntil) {
            return effects.claimDue(now, limit, leaseToken, leaseUntil);
          }

          @Override
          public List<String> claimDueDeadlines(
              Instant now, int limit, String leaseToken, Instant leaseUntil) {
            return List.of();
          }
        };
    return new ProcessEffectRelay(
        claim,
        effects,
        instances,
        new ProcessPayloadCodecRegistry(List.of(new PassThroughCodec())),
        new EffectDispatcherRegistry(List.of(dispatcher)),
        new RollingBackUnitOfWork(effects, instances),
        new ExponentialBackoffPolicy(
            Duration.ofMillis(100), Duration.ofSeconds(10), 2.0, 0.0, maxAttempts, () -> 0.5),
        clock,
        10,
        Duration.ofMinutes(5),
        leaseTokens);
  }

  private ProcessEffectRelay relay() {
    return relay(() -> "lease-A", 3);
  }

  private void stage(String effectId, long seq) {
    effects.insert(effects.stage(effectId, INSTANCE, seq, ProcessEffectKind.DISPATCH_COMMAND), NOW);
  }

  @Test
  void anEffectIsDeliveredAndRecordedDelivered() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    stage("effect-1", 1);

    assertEquals(1, relay().pollOnce());

    assertEquals(List.of("effect-1"), dispatcher.dispatched);
    assertEquals(EffectStatus.DELIVERED, effects.row("effect-1").status());
    assertNull(effects.row("effect-1").leaseToken(), "and it no longer holds a lease");
  }

  @Test
  void anInstancesEffectsGoOutInTheOrderTheyWereStaged() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    stage("effect-3", 3);
    stage("effect-1", 1);
    stage("effect-2", 2);

    relay().pollOnce();

    assertEquals(List.of("effect-1", "effect-2", "effect-3"), dispatcher.dispatched);
  }

  @Test
  void anOperatorCancelIsHonouredEvenForAnEffectAlreadyInFlight() {
    instances.given(INSTANCE, ProcessLifecycle.CANCELLED);
    stage("effect-1", 1);

    assertEquals(0, relay().pollOnce());

    // "No new external side effect after cancel returns" — not merely "cancel the effects nobody
    // has picked up yet". A charge or reservation escaping here is unrecoverable by definition.
    assertEquals(List.of(), dispatcher.dispatched, "nothing reached the outside world");
    assertEquals(EffectStatus.CANCELLED, effects.row("effect-1").status());
  }

  @Test
  void theOwningTenantIsBoundAroundTheDispatchEvenThoughTheRelayThreadHasNone() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    stage("effect-1", 1);

    relay().pollOnce();

    // The handler, or the event's producer, may read the ambient tenant to scope its own tables.
    // On a relay thread nothing bound it, which is why the row carries the owning tenant.
    assertEquals(List.of("acme"), dispatcher.tenantsSeen);
  }

  @Test
  void aFailedDispatchSpendsOneAttemptAndComesBackLater() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    stage("effect-1", 1);
    dispatcher.failing.add("effect-1");

    assertEquals(0, relay().pollOnce());

    assertEquals(EffectStatus.PENDING, effects.row("effect-1").status());
    assertEquals(1, effects.row("effect-1").attempts());
    assertEquals(
        NOW.plusMillis(100),
        effects.row("effect-1").nextAttemptAt(),
        "spaced by the backoff for the attempt that just failed, so a downstream that is down is "
            + "not hammered");
    assertTrue(effects.row("effect-1").lastError().contains("downstream is down"));
  }

  @Test
  void anEffectIsGivenUpOnOnlyOnceItsBudgetIsGoneAndItSuspendsTheInstance() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    stage("effect-1", 1);
    dispatcher.failing.add("effect-1");

    for (int attempt = 1; attempt <= 3; attempt++) {
      clock.advance(Duration.ofHours(1));
      relay().pollOnce();
    }

    assertEquals(EffectStatus.DEAD, effects.row("effect-1").status());
    // A process whose effect never landed has a state nobody should build on, so it stops rather
    // than carrying on as if the side effect had happened.
    assertEquals(ProcessLifecycle.SUSPENDED, instances.row(INSTANCE).lifecycle());
    assertEquals("EFFECT", instances.suspensionSourceOf(INSTANCE));
    assertEquals(
        java.util.Optional.of(ProcessLifecycle.RUNNING),
        instances.row(INSTANCE).resumeLifecycle(),
        "and it remembers what to go back to once an operator redrives the effect");
  }

  @Test
  void anEffectThatExhaustsItsRetriesOnAnAlreadyEndedInstanceDoesNotTryToSuspendIt() {
    instances.given(INSTANCE, ProcessLifecycle.FAILED);
    stage("effect-1", 1);
    dispatcher.failing.add("effect-1");

    for (int attempt = 1; attempt <= 3; attempt++) {
      clock.advance(Duration.ofHours(1));
      relay().pollOnce();
    }

    assertEquals(EffectStatus.DEAD, effects.row("effect-1").status());
    assertEquals(
        ProcessLifecycle.FAILED,
        instances.row(INSTANCE).lifecycle(),
        "a finished process has nothing to suspend, and rewriting a terminal lifecycle would lose "
            + "the outcome it already reached");
  }

  @Test
  void aWorkerWhoseLeaseExpiredCannotCompleteAnEffectSomebodyElseTookOver() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    stage("effect-1", 1);
    // The first worker claims it and then stalls long enough for its lease to run out.
    relayThatOnlyClaims("lease-A").pollOnce();
    effects.expireLease("effect-1");

    // A second worker reclaims and delivers it.
    assertEquals(1, relay(() -> "lease-B", 3).pollOnce());

    // Now the first worker comes back and tries to complete it. Its write must not land: the
    // effect is the second worker's now, and a stale mark could undo or misreport that delivery.
    assertEquals(0, effects.markDelivered("effect-1", "lease-A", NOW));
    assertEquals(0, effects.scheduleRetry("effect-1", "lease-A", NOW, "stale", NOW));
    assertEquals(0, effects.markDead("effect-1", "lease-A", "stale", NOW));
    assertEquals(EffectStatus.DELIVERED, effects.row("effect-1").status());
  }

  @Test
  void aReclaimAfterALeaseExpiresDoesNotSpendAnyOfTheRetryBudget() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    stage("effect-1", 1);
    relayThatOnlyClaims("lease-A").pollOnce();
    effects.expireLease("effect-1");

    relayThatOnlyClaims("lease-B").pollOnce();

    // attempts is bumped by a failure, never by claiming — otherwise a slow worker would burn
    // through the budget of an effect that was never actually attempted.
    assertEquals(0, effects.row("effect-1").attempts());
  }

  @Test
  void aCrashedWorkersEffectIsRedeliveredUnderTheSameIdSoTheConsumerCanDedupe() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    stage("effect-1", 1);
    relayThatOnlyClaims("lease-A").pollOnce();
    effects.expireLease("effect-1");

    relay(() -> "lease-B", 3).pollOnce();

    assertEquals(
        List.of("effect-1"),
        dispatcher.dispatched,
        "at-least-once means the same effect id, so the consumer's inbox recognises the duplicate");
  }

  @Test
  void everyPollTakesAFreshLeaseSoTwoWorkersCanNeverHoldTheSameOne() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    stage("effect-1", 1);
    stage("effect-2", 2);

    List<String> issued = new ArrayList<>();
    Supplier<String> tokens =
        () -> {
          String token = "lease-" + issued.size();
          issued.add(token);
          return token;
        };
    relay(tokens, 3).pollOnce();
    relay(tokens, 3).pollOnce();

    assertNotEquals(issued.get(0), issued.get(1));
  }

  @Test
  void anEffectWhoseInstanceHasVanishedIsStillDispatched() {
    // No instance row at all: the cancellation fence only refuses when it positively finds a
    // cancelled owner, so a missing row must not silently swallow the effect.
    stage("effect-1", 1);

    assertEquals(1, relay().pollOnce());

    assertEquals(List.of("effect-1"), dispatcher.dispatched);
  }

  /** A relay whose dispatch always fails, used only to take a claim and leave it in flight. */
  private ProcessEffectRelay relayThatOnlyClaims(String token) {
    return new ProcessEffectRelay(
        new ProcessClaimStrategy() {
          @Override
          public String id() {
            return "in-memory";
          }

          @Override
          public List<String> claimDueEffects(
              Instant now, int limit, String leaseToken, Instant leaseUntil) {
            effects.claimDue(now, limit, leaseToken, leaseUntil);
            return List.of();
          }

          @Override
          public List<String> claimDueDeadlines(
              Instant now, int limit, String leaseToken, Instant leaseUntil) {
            return List.of();
          }
        },
        effects,
        instances,
        new ProcessPayloadCodecRegistry(List.of(new PassThroughCodec())),
        new EffectDispatcherRegistry(List.of(dispatcher)),
        new RollingBackUnitOfWork(effects, instances),
        new ExponentialBackoffPolicy(
            Duration.ofMillis(100), Duration.ofSeconds(10), 2.0, 0.0, 3, () -> 0.5),
        clock,
        10,
        Duration.ofMinutes(5),
        () -> token);
  }
}
