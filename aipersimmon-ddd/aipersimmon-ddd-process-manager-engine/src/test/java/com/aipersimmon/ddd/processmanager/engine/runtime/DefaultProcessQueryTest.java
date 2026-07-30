package com.aipersimmon.ddd.processmanager.engine.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.processmanager.codec.EncodedPayload;
import com.aipersimmon.ddd.processmanager.definition.MaxLifetimeExceeded;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;
import com.aipersimmon.ddd.processmanager.engine.store.DeadlineStatus;
import com.aipersimmon.ddd.processmanager.engine.store.EffectStatus;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessEffectStore;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceCriteria;
import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.runtime.ProcessView;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The operator-facing read side. Two of its answers are load-bearing rather than convenience: a
 * lookup by business key is scoped to the ambient tenant, and a {@link ProcessRef} that names a
 * real instance but the wrong type or key is refused instead of quietly answering about a different
 * instance.
 */
class DefaultProcessQueryTest {

  private static final Instant NOW = Instant.parse("2026-07-30T10:00:00Z");
  private static final ProcessType ORDERING = new ProcessType("Ordering");
  private static final ProcessBusinessKey ORDER_1 = new ProcessBusinessKey("order-1");
  private static final ProcessInstanceId INSTANCE = new ProcessInstanceId("instance-1");
  private static final ProcessRef REF = new ProcessRef(INSTANCE, ORDERING, ORDER_1);
  private static final ProcessStep AWAITING_PAYMENT = new ProcessStep("awaiting-payment");

  private final InMemoryProcessInstanceStore instances = new InMemoryProcessInstanceStore();
  private final InMemoryProcessTransitionStore transitions =
      new InMemoryProcessTransitionStore(instances);
  private final InMemoryProcessEffectStore effects = new InMemoryProcessEffectStore();
  private final InMemoryProcessDeadlineStore deadlines = new InMemoryProcessDeadlineStore();

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

  private final DefaultProcessQuery query =
      new DefaultProcessQuery(instances, transitions, effects, deadlines, CLOCK);

  @AfterEach
  void unbindTheTenant() {
    TenantContext.clear();
  }

  @Test
  void anInstanceIsReportedWithItsRuntimeMetadataAndNoBusinessState() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);

    ProcessView view = query.find(REF).orElseThrow();

    assertEquals(REF, view.processRef());
    assertEquals(ProcessLifecycle.RUNNING, view.lifecycle());
    assertEquals(AWAITING_PAYMENT, view.step());
    assertEquals(Optional.empty(), view.outcome());
  }

  @Test
  void anInstanceThatIsNotThereIsEmptyRatherThanAnError() {
    assertEquals(Optional.empty(), query.find(REF));
  }

  @Test
  void aRefThatNamesARealInstanceButTheWrongProcessIsRefused() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    ProcessRef wrong = new ProcessRef(INSTANCE, new ProcessType("Shipping"), ORDER_1);

    // Answering about the instance that happens to carry this id would hand an operator — or a
    // caller acting on the answer — a different process than the one they asked about.
    IllegalArgumentException refused =
        assertThrows(IllegalArgumentException.class, () -> query.find(wrong));
    assertTrue(refused.getMessage().contains("process ref mismatch"), refused.getMessage());
  }

  @Test
  void aBusinessKeyResolvesToARefWithinTheBoundTenantOnly() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);

    TenantContext.set(new TenantId("acme"));
    assertEquals(Optional.of(REF), query.findRef(ORDERING, ORDER_1));

    // A business key is tenant-relative, so an unscoped read here would let one tenant address —
    // and then advance — another tenant's instance.
    TenantContext.set(new TenantId("globex"));
    assertEquals(Optional.empty(), query.findRef(ORDERING, ORDER_1));
  }

  @Test
  void instancesArePagedByTheCriteria() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    instances.given(new ProcessInstanceId("instance-2"), ProcessLifecycle.RUNNING);

    List<ProcessView> page = query.search(ProcessInstanceCriteria.any(), 1, 1);

    assertEquals(1, page.size());
    assertEquals("instance-2", page.get(0).processRef().instanceId().value());
  }

  @Test
  void theTimelineIsTheInstancesTransitionsInOrder() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    transitions.append(
        transitions.entry(
            "t-1", INSTANCE, "m-1", ProcessLifecycle.RUNNING, AWAITING_PAYMENT, "START"),
        NOW);
    transitions.append(
        transitions.entry(
            "t-2", INSTANCE, "m-2", ProcessLifecycle.RUNNING, AWAITING_PAYMENT, "ADVANCE"),
        NOW);

    assertEquals(
        List.of("t-1", "t-2"),
        query.timeline(REF).stream().map(view -> view.transitionId()).toList());
  }

  @Test
  void effectsAndDeadlinesAreOfferedByStatusForARedriveWorklist() {
    effects.insert(effects.stage("e-1", INSTANCE, 1, ProcessEffectKind.DISPATCH_COMMAND), NOW);
    deadlines.schedule(deadlines.arm("d-1", INSTANCE, new DeadlineName("REVIEW"), NOW), NOW);

    assertEquals(1, query.effects(EffectStatus.PENDING, 10).size());
    assertEquals(0, query.effects(EffectStatus.DEAD, 10).size());
    assertEquals(1, query.deadlines(DeadlineStatus.PENDING, 10).size());
    assertEquals(0, query.deadlines(DeadlineStatus.DEAD, 10).size());
  }

  @Test
  void anActiveInstanceIdlePastTheThresholdIsOfferedAsStuck() {
    instances.given(INSTANCE, ProcessLifecycle.RUNNING);
    instances.touch(INSTANCE, NOW.minus(Duration.ofHours(3)));
    instances.given(new ProcessInstanceId("instance-2"), ProcessLifecycle.RUNNING);
    instances.touch(new ProcessInstanceId("instance-2"), NOW);

    List<ProcessView> stuck = query.stuckInstances(Duration.ofHours(1), 10);

    // The complement of the max-lifetime backstop: nothing is overdue, the instance is simply not
    // moving, which is what a lost wakeup looks like from outside.
    assertEquals(1, stuck.size());
    assertEquals(INSTANCE, stuck.get(0).processRef().instanceId());
  }

  @Test
  void theBackstopInputCarriesNoBusinessFieldsAndRoundTripsAsNothing() {
    MaxLifetimeExceededCodec codec = new MaxLifetimeExceededCodec();

    EncodedPayload encoded = codec.encode(new MaxLifetimeExceeded());

    assertEquals(0, encoded.data().length, "there is nothing to say beyond that time ran out");
    assertEquals(new MaxLifetimeExceeded(), codec.decode(encoded));
    assertEquals(MaxLifetimeExceeded.class, codec.javaType());
  }
}
