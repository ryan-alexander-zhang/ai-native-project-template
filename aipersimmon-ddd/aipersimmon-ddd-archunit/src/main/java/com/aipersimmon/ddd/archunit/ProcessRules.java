package com.aipersimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.aipersimmon.ddd.core.annotation.Repository;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Set;

/**
 * Process-manager rules: a {@code ProcessDefinition} is the pure decision function the durable
 * runtime replays, so it does no I/O and reads nothing from the environment.
 *
 * <p>{@link #processDefinitionsShouldBePure()} is bundled into {@link AiPersimmonDddRules#all()}.
 * Like {@link OperationLogRules}, everything here matches the component by <em>name</em>, so this
 * jar carries no compile dependency on the process-manager modules and does not drag them onto a
 * consumer's classpath; a project that uses no process manager has no matching classes and passes
 * vacuously.
 */
public final class ProcessRules {

  /** The definition contract, matched by fully-qualified name to avoid a compile dependency. */
  private static final String PROCESS_DEFINITION =
      "com.aipersimmon.ddd.processmanager.definition.ProcessDefinition";

  /**
   * The dispatch and publication ports a definition must not reach for. Each has a sanctioned
   * counterpart among the effects the definition <em>returns</em> — {@code DispatchCommand} for the
   * command bus, {@code PublishIntegrationEvent} for the publisher — which is the whole difference
   * this rule protects.
   */
  private static final Set<String> FORBIDDEN_PORTS =
      Set.of(
          "com.aipersimmon.ddd.cqrs.CommandBus",
          "com.aipersimmon.ddd.cqrs.QueryBus",
          "com.aipersimmon.ddd.application.IntegrationEvents",
          "com.aipersimmon.ddd.application.DurableIntegrationEvents",
          "com.aipersimmon.ddd.application.DomainEvents",
          "com.aipersimmon.ddd.application.InboundEvents");

  /**
   * Package prefixes a definition must not touch: the outer layers of the consuming application,
   * and the technical machinery of doing I/O. Spring is included because a container-managed
   * collaborator is how the other items get in.
   */
  private static final List<String> FORBIDDEN_PACKAGES =
      List.of("org.springframework.", "java.sql.", "javax.sql.", "java.net.http.");

  /** The outer layers of the consuming application, matched by package segment. */
  private static final DescribedPredicate<JavaClass> OUTER_LAYERS =
      JavaClass.Predicates.resideInAnyPackage("..infrastructure..", "..adapter..");

  private ProcessRules() {}

  /**
   * A {@code ProcessDefinition} is pure: it performs no I/O (see {@link
   * #processDefinitionsShouldNotPerformIo()}) and reads no ambient time or randomness (see {@link
   * #processDefinitionsShouldBeDeterministic()}).
   *
   * <p>This mechanises what {@code ProcessDefinition}'s own contract already states in prose — "a
   * pure, deterministic decision object … it must do no I/O — no repository, HTTP, command bus,
   * integration-event publish, system clock, randomness, Spring bean, or third-party SDK". The
   * runtime depends on it in a way that is easy to violate and hard to notice: a definition is
   * re-entered on redelivery and on recovery, so a call made <em>inside</em> it happens again every
   * time, uncounted by the effect ledger and outside the transaction that persists the transition.
   * The same call expressed as a returned effect is recorded with the transition, dispatched once
   * by the relay under a stable message identity, and retried by the relay rather than by accident.
   *
   * <p>A definition that reads the clock breaks the other half: deadlines are the runtime's job
   * ({@code ScheduleDeadline}), and a decision that branches on {@code Instant.now()} reaches a
   * different conclusion when it is replayed than when it first ran, which is precisely the
   * situation the durable state exists to rule out.
   *
   * <p>Part of {@link AiPersimmonDddRules#all()}; matches nothing (and so passes) in a project that
   * declares no process definitions.
   */
  public static ArchRule processDefinitionsShouldBePure() {
    return CompositeArchRule.of(processDefinitionsShouldNotPerformIo())
        .and(processDefinitionsShouldBeDeterministic())
        .as(
            "ProcessDefinition implementations should be pure: no I/O, no ambient time or "
                + "randomness");
  }

  /**
   * One half of {@link #processDefinitionsShouldBePure()}: a {@code ProcessDefinition} depends on
   * no command or query bus, no event publisher, no {@link Repository @Repository} port, no
   * infrastructure or adapter class, and no Spring / SQL / network type. Everything it wants the
   * world to do is returned as an effect for the runtime to carry out. Exposed separately so a
   * project can state that half on its own.
   */
  public static ArchRule processDefinitionsShouldNotPerformIo() {
    return classes()
        .that(areProcessDefinitions())
        .should(notReachOutward())
        .as("ProcessDefinition implementations should not perform I/O")
        .because(
            "a definition is re-entered on every redelivery and recovery, so a call made inside "
                + "it repeats uncounted by the effect ledger and outside the transaction that "
                + "persists the transition; the same call returned as an effect is recorded with "
                + "the transition and dispatched once under a stable message identity")
        .allowEmptyShould(true);
  }

  /**
   * The other half of {@link #processDefinitionsShouldBePure()}: a {@code ProcessDefinition} reads
   * no ambient clock and no fresh randomness, so replaying it on the same state and input reaches
   * the same decision. Timing belongs to the runtime, through the {@code ScheduleDeadline} effect
   * and the deadline that fires back into {@code react}. Exposed separately so a project can state
   * that half on its own.
   */
  public static ArchRule processDefinitionsShouldBeDeterministic() {
    return classes()
        .that(areProcessDefinitions())
        .should(DeterminismRules.notUseAmbientTimeOrRandomness())
        .as("ProcessDefinition implementations should not read ambient time or randomness")
        .because(
            "a decision that branches on the wall clock or a fresh random value reaches a "
                + "different conclusion when replayed than when it first ran, which is the "
                + "situation the durable state exists to rule out; deadlines are the runtime's job")
        .allowEmptyShould(true);
  }

  /** A class implementing the {@code ProcessDefinition} contract, matched by name. */
  private static DescribedPredicate<JavaClass> areProcessDefinitions() {
    return DescribedPredicate.describe(
        "implement ProcessDefinition",
        javaClass ->
            javaClass.isAssignableTo(PROCESS_DEFINITION)
                && !javaClass.getName().equals(PROCESS_DEFINITION));
  }

  /**
   * Reports a violation for each dependency of a definition that leaves its own world: a bus or
   * publisher port, a {@code @Repository} port, an infrastructure or adapter class, or a Spring /
   * SQL / network type. Used with {@code classes().should(...)}, so a {@code violated} event is a
   * rule violation.
   */
  private static ArchCondition<JavaClass> notReachOutward() {
    return new ArchCondition<>("not depend on buses, publishers, repositories or infrastructure") {
      @Override
      public void check(JavaClass definition, ConditionEvents events) {
        for (Dependency dependency : definition.getDirectDependenciesFromSelf()) {
          String reason = outwardReach(dependency.getTargetClass());
          if (reason != null) {
            events.add(
                SimpleConditionEvent.violated(
                    dependency, dependency.getDescription() + " — " + reason));
          }
        }
      }
    };
  }

  /**
   * Why the given dependency target is out of bounds for a definition, or {@code null} when it is
   * not. A repository port is recognised by the core {@code @Repository} annotation and the outer
   * layers by their package segment, so both hold whatever a project names its classes.
   *
   * <p>Annotations are exempt from the package check, and that exemption is load-bearing rather
   * than a concession. A definition is normally a Spring bean — that is how the registry finds it —
   * and it configures its timeouts with {@code @Value} or {@code @ConfigurationProperties}. Neither
   * is a collaborator: an annotation cannot perform I/O, and a duration resolved once at
   * construction is a constant by the time any decision runs. Written as a flat "no Spring", this
   * rule reported the framework's own reference process definition, which is the clearest possible
   * evidence that the line belongs at "holds a Spring collaborator", not at "mentions Spring".
   */
  private static String outwardReach(JavaClass target) {
    String name = target.getName();
    if (FORBIDDEN_PORTS.contains(name)) {
      return "a definition returns effects instead of calling a bus or publisher";
    }
    if (target.isAnnotatedWith(Repository.class)) {
      return "a definition decides from the state it is given, never by loading an aggregate";
    }
    if (OUTER_LAYERS.test(target)) {
      return "a definition is replayed by the runtime, so it must not reach into an outer layer";
    }
    if (target.isAnnotation()) {
      return null;
    }
    return FORBIDDEN_PACKAGES.stream().anyMatch(name::startsWith)
        ? "a definition holds no framework, database or network collaborator"
        : null;
  }
}
