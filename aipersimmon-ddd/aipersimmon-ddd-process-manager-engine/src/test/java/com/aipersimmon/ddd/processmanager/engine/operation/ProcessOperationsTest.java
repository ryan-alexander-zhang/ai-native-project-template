package com.aipersimmon.ddd.processmanager.engine.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;
import com.aipersimmon.ddd.processmanager.engine.store.DeadlineStatus;
import com.aipersimmon.ddd.processmanager.engine.store.EffectStatus;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessEffectStore;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessTransitionView;
import com.aipersimmon.ddd.processmanager.engine.store.RollingBackUnitOfWork;
import com.aipersimmon.ddd.processmanager.exception.ProcessNotFoundException;
import com.aipersimmon.ddd.processmanager.exception.StaleProcessRevisionException;
import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessOutcome;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Operator recovery, which is the last line when everything automatic has already given up.
 *
 * <p>Two things it must never do, and both are here: leave an instance SUSPENDED with nothing left
 * to redrive (so nobody ever comes back for it), and resume one while dead work still stands (so it
 * carries on from a state the failed work never produced). Every action leaves an audited operator
 * transition, and each one either happens whole or not at all — a half-applied redrive is worse
 * than a refused one, because it looks like it worked.
 */
class ProcessOperationsTest {

  private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");
  private static final ProcessInstanceId INSTANCE = new ProcessInstanceId("instance-1");
  private static final ProcessType ORDERING = new ProcessType("Ordering");
  private static final ProcessBusinessKey ORDER_1 = new ProcessBusinessKey("order-1");
  private static final ProcessRef REF = new ProcessRef(INSTANCE, ORDERING, ORDER_1);
  private static final DeadlineName REVIEW = new DeadlineName("REVIEW");

  private final InMemoryProcessInstanceStore instances = new InMemoryProcessInstanceStore();
  private final InMemoryProcessTransitionStore transitions =
      new InMemoryProcessTransitionStore(instances);
  private final InMemoryProcessEffectStore effects = new InMemoryProcessEffectStore();
  private final InMemoryProcessDeadlineStore deadlines = new InMemoryProcessDeadlineStore();
  private final RollingBackUnitOfWork unitOfWork =
      new RollingBackUnitOfWork(instances, transitions, effects, deadlines);
  private final List<String> minted = new ArrayList<>();

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

  private final ProcessOperations operations =
      new ProcessOperations(
          instances,
          transitions,
          effects,
          deadlines,
          unitOfWork,
          CLOCK,
          () -> {
            String id = "op-" + (minted.size() + 1);
            minted.add(id);
            return id;
          });

  // --- fixtures ---------------------------------------------------------------------------------

  /** An instance suspended by a failed effect or deadline, remembering where to go back to. */
  private void suspendedInstance() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    instances.suspend(INSTANCE, ProcessLifecycle.RUNNING, "gave up", "EFFECT", "work-1", NOW);
  }

  /** Stages an effect and drives it all the way to DEAD, the way an exhausted relay does. */
  private void deadEffect(String effectId) {
    effects.insert(effects.stage(effectId, INSTANCE, 1, ProcessEffectKind.DISPATCH_COMMAND), NOW);
    effects.claimDue(NOW, 10, "lease-A", NOW.plusSeconds(60));
    effects.markDead(effectId, "lease-A", "downstream is down", NOW);
  }

  /** Arms a deadline and drives it to DEAD, the way an exhausted worker does. */
  private void deadDeadline(String deadlineId) {
    deadlines.schedule(deadlines.arm(deadlineId, INSTANCE, REVIEW, NOW), NOW);
    deadlines.claimDue(NOW, 10, "lease-A", NOW.plusSeconds(60));
    deadlines.markDead(deadlineId, "lease-A", "definition refused it", NOW);
  }

  private List<ProcessTransitionView> auditTrail() {
    return transitions.timeline(INSTANCE);
  }

  // --- redriving a dead effect ------------------------------------------------------------------

  @Test
  void redrivingTheLastDeadEffectReturnsItToTheQueueAndResumesTheInstance() {
    suspendedInstance();
    deadEffect("effect-1");

    operations.redriveEffect("effect-1", "alice", "downstream is back");

    assertEquals(EffectStatus.PENDING, effects.row("effect-1").status());
    assertEquals(0, effects.row("effect-1").attempts(), "with a fresh budget, not the spent one");
    assertEquals(
        ProcessLifecycle.RUNNING,
        instances.row(INSTANCE).lifecycle(),
        "and back to the lifecycle it recorded when it suspended");
    assertEquals(Optional.empty(), instances.row(INSTANCE).resumeLifecycle());
  }

  @Test
  void aRedriveLeavesAnAuditedOperatorTransition() {
    suspendedInstance();
    deadEffect("effect-1");

    operations.redriveEffect("effect-1", "alice", "downstream is back");

    List<ProcessTransitionView> trail = auditTrail();
    assertEquals(1, trail.size());
    assertEquals("OPERATOR_REDRIVE_EFFECT", trail.get(0).transitionKind());
    assertEquals(Optional.of("alice"), trail.get(0).operator());
    assertEquals(Optional.of("downstream is back"), trail.get(0).reason());
    // The lifecycle move is the resume, not this row: an operator action is recorded as a fact
    // about who did what, and never as a decision the definition did not take.
    assertEquals("SUSPENDED", trail.get(0).toLifecycle());
    assertEquals(Optional.of("SUSPENDED"), trail.get(0).fromLifecycle());
  }

  @Test
  void anInstanceIsNotResumedWhileAnotherDeadEffectStillStandsBehindIt() {
    suspendedInstance();
    deadEffect("effect-1");
    deadEffect("effect-2");

    operations.redriveEffect("effect-1", "alice", "one of two");

    // Resuming here would let the process carry on from a state effect-2 never produced — the
    // instance would look healthy while a side effect it depends on had simply not happened.
    assertEquals(ProcessLifecycle.SUSPENDED, instances.row(INSTANCE).lifecycle());
  }

  @Test
  void anInstanceIsNotResumedWhileADeadDeadlineStillStandsBehindIt() {
    suspendedInstance();
    deadEffect("effect-1");
    deadDeadline("deadline-1");

    operations.redriveEffect("effect-1", "alice", "the effect, not the timer");

    // The two worklists are separate, so an operator clearing one can easily believe the instance
    // is free; the check spans both for exactly that reason.
    assertEquals(ProcessLifecycle.SUSPENDED, instances.row(INSTANCE).lifecycle());
  }

  @Test
  void redrivingAnEffectOnARunningInstanceLeavesTheLifecycleAlone() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    deadEffect("effect-1");

    operations.redriveEffect("effect-1", "alice", "it never suspended");

    // A DEAD effect does not always suspend — an already-ended or still-running instance keeps its
    // lifecycle, and a redrive must not invent a resume for it.
    assertEquals(ProcessLifecycle.RUNNING, instances.row(INSTANCE).lifecycle());
  }

  @Test
  void anInstanceWithNoRecordedResumeLifecycleGoesBackToRunning() {
    instances.given(INSTANCE, ProcessLifecycle.SUSPENDED);
    deadEffect("effect-1");

    operations.redriveEffect("effect-1", "alice", "no resume recorded");

    assertEquals(ProcessLifecycle.RUNNING, instances.row(INSTANCE).lifecycle());
  }

  @Test
  void redrivingAnEffectThatDoesNotExistIsRefused() {
    assertThrows(
        IllegalArgumentException.class, () -> operations.redriveEffect("nope", "alice", "typo"));
  }

  @Test
  void redrivingAnEffectThatIsNotDeadChangesNothingAtAll() {
    suspendedInstance();
    effects.insert(effects.stage("effect-1", INSTANCE, 1, ProcessEffectKind.DISPATCH_COMMAND), NOW);

    assertThrows(
        IllegalStateException.class,
        () -> operations.redriveEffect("effect-1", "alice", "it is still pending"));

    // Refusing is only half of it: the audit row must not survive an action that did not happen,
    // or the trail records a redrive nobody performed.
    assertEquals(List.of(), auditTrail());
    assertEquals(ProcessLifecycle.SUSPENDED, instances.row(INSTANCE).lifecycle());
  }

  // --- redriving a dead deadline ----------------------------------------------------------------

  @Test
  void redrivingTheLastDeadDeadlineReturnsItToTheQueueAndResumesTheInstance() {
    suspendedInstance();
    deadDeadline("deadline-1");

    operations.redriveDeadline("deadline-1", 1, "alice", "the definition handles it now");

    assertEquals(DeadlineStatus.PENDING, deadlines.row("deadline-1").status());
    assertEquals(0, deadlines.row("deadline-1").attempts());
    assertEquals(ProcessLifecycle.RUNNING, instances.row(INSTANCE).lifecycle());
    assertEquals("OPERATOR_REDRIVE_DEADLINE", auditTrail().get(0).transitionKind());
  }

  @Test
  void redrivingADeadlineAtTheWrongGenerationIsRefusedAndChangesNothing() {
    suspendedInstance();
    deadDeadline("deadline-1");

    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> operations.redriveDeadline("deadline-1", 7, "alice", "stale console"));

    // The generation is what an operator is really asserting: "the timer I am looking at". Between
    // reading the console and clicking, a reschedule can have moved on, and redriving the old one
    // would fire a timeout the flow has already replaced.
    assertTrue(refused.getMessage().contains("generation 1"), refused.getMessage());
    assertEquals(DeadlineStatus.DEAD, deadlines.row("deadline-1").status());
    assertEquals(List.of(), auditTrail());
  }

  @Test
  void redrivingADeadlineThatDoesNotExistIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> operations.redriveDeadline("nope", 1, "alice", "typo"));
  }

  @Test
  void redrivingADeadlineThatIsNotDeadChangesNothingAtAll() {
    suspendedInstance();
    deadlines.schedule(deadlines.arm("deadline-1", INSTANCE, REVIEW, NOW), NOW);

    assertThrows(
        IllegalStateException.class,
        () -> operations.redriveDeadline("deadline-1", 1, "alice", "it is still pending"));

    assertEquals(List.of(), auditTrail());
  }

  @Test
  void deadWorkWhoseInstanceIsGoneIsRefusedRatherThanRedrivenIntoNothing() {
    ProcessInstanceId orphan = new ProcessInstanceId("instance-gone");
    effects.insert(effects.stage("effect-1", orphan, 1, ProcessEffectKind.DISPATCH_COMMAND), NOW);
    effects.claimDue(NOW, 10, "lease-A", NOW.plusSeconds(60));
    effects.markDead("effect-1", "lease-A", "downstream is down", NOW);
    deadlines.schedule(deadlines.arm("deadline-1", orphan, REVIEW, NOW), NOW);
    deadlines.claimDue(NOW, 10, "lease-A", NOW.plusSeconds(60));
    deadlines.markDead("deadline-1", "lease-A", "definition refused it", NOW);

    // The relay deliberately tolerates a missing instance — an effect still has to go out, and the
    // cancellation fence only refuses when it positively finds a cancelled owner. An operator
    // action is the opposite case: it exists to put an instance back to work, and there is no
    // instance, so redriving would return the work to a queue that leads nowhere.
    assertThrows(
        IllegalStateException.class, () -> operations.redriveEffect("effect-1", "alice", "orphan"));
    assertThrows(
        IllegalStateException.class,
        () -> operations.redriveDeadline("deadline-1", 1, "alice", "orphan"));
    assertEquals(EffectStatus.DEAD, effects.row("effect-1").status(), "and it stays where it was");
    assertEquals(DeadlineStatus.DEAD, deadlines.row("deadline-1").status());
  }

  // --- cancelling a process ---------------------------------------------------------------------

  @Test
  void cancellingEndsTheInstanceAndTakesItsPendingWorkWithIt() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    effects.insert(effects.stage("effect-1", INSTANCE, 1, ProcessEffectKind.DISPATCH_COMMAND), NOW);
    deadlines.schedule(deadlines.arm("deadline-1", INSTANCE, REVIEW, NOW.plusSeconds(600)), NOW);

    operations.cancelProcess(REF, 0, "alice", "customer withdrew");

    assertEquals(ProcessLifecycle.CANCELLED, instances.row(INSTANCE).lifecycle());
    assertEquals(
        Optional.of(new ProcessOutcome("PROCESS_CANCELLED")), instances.row(INSTANCE).outcome());
    assertEquals(new ProcessRevision(1), instances.row(INSTANCE).revision());
    assertEquals(EffectStatus.CANCELLED, effects.row("effect-1").status());
    // Left armed, the timer could never fire (the claim only offers active instances) yet would
    // keep the backlog signal degraded forever with a monotonically growing age.
    assertEquals(DeadlineStatus.CANCELLED, deadlines.row("deadline-1").status());
    assertEquals("OPERATOR_CANCEL", auditTrail().get(0).transitionKind());
    assertEquals("CANCELLED", auditTrail().get(0).toLifecycle());
    assertEquals(Optional.of("customer withdrew"), auditTrail().get(0).reason());
  }

  @Test
  void cancellingFromAStaleRevisionIsRefusedAndChangesNothing() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    effects.insert(effects.stage("effect-1", INSTANCE, 1, ProcessEffectKind.DISPATCH_COMMAND), NOW);

    StaleProcessRevisionException refused =
        assertThrows(
            StaleProcessRevisionException.class,
            () -> operations.cancelProcess(REF, 7, "alice", "stale console"));

    // The operator is cancelling the process they were looking at. If it advanced since, the
    // decision to cancel was made about a different state and has to be retaken.
    assertEquals(new ProcessRevision(0), refused.actual());
    assertEquals(ProcessLifecycle.RUNNING, instances.row(INSTANCE).lifecycle());
    assertEquals(EffectStatus.PENDING, effects.row("effect-1").status());
    assertEquals(List.of(), auditTrail());
  }

  @Test
  void cancellingAnInstanceThatAlreadyEndedIsAQuietNoOp() {
    instances.given(INSTANCE, ProcessLifecycle.COMPLETED);

    operations.cancelProcess(REF, 0, "alice", "too late");

    // Not an error — a console can easily be a moment behind — but it must not overwrite the
    // outcome the process actually reached, nor record a cancel that did not happen.
    assertEquals(ProcessLifecycle.COMPLETED, instances.row(INSTANCE).lifecycle());
    assertEquals(List.of(), auditTrail());
  }

  @Test
  void cancellingAnInstanceThatDoesNotExistIsRefused() {
    assertThrows(
        ProcessNotFoundException.class, () -> operations.cancelProcess(REF, 0, "alice", "typo"));
  }

  @Test
  void aRefThatNamesADifferentProcessThanTheStoredInstanceIsRefused() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    ProcessRef wrong = new ProcessRef(INSTANCE, new ProcessType("Shipping"), ORDER_1);

    // Cancelling the wrong process on a copy-pasted id is unrecoverable in the direction that
    // matters, so the id alone is not enough to act on.
    assertThrows(
        IllegalArgumentException.class,
        () -> operations.cancelProcess(wrong, 0, "alice", "wrong process"));
    assertEquals(ProcessLifecycle.RUNNING, instances.row(INSTANCE).lifecycle());
  }

  @Test
  void everyOperatorActionTakesItsOwnAuditIdentity() {
    suspendedInstance();
    deadEffect("effect-1");
    deadDeadline("deadline-1");

    operations.redriveEffect("effect-1", "alice", "one");
    operations.redriveDeadline("deadline-1", 1, "bob", "two");

    // A synthetic input identity per row, so an operator action can never collide with a business
    // input on the dedup key that makes inputs apply once.
    assertEquals(List.of("op-1", "op-2"), minted);
    assertEquals(
        List.of("op-1", "op-2"),
        auditTrail().stream().map(ProcessTransitionView::transitionId).toList());
  }
}
