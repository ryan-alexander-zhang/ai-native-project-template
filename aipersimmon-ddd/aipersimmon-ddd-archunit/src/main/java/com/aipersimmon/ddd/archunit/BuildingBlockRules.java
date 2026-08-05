package com.aipersimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.annotation.Entity;
import com.aipersimmon.ddd.core.annotation.Service;
import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Tactical building-block rules: where the domain model's building blocks live and the shape they
 * must have — aggregate roots, entities, value objects, and domain services. All are bundled into
 * {@link AiPersimmonDddRules#all()}.
 */
public final class BuildingBlockRules {

  private BuildingBlockRules() {}

  /**
   * The tactical building blocks that make up an aggregate reside where they belong: a type
   * carrying {@link AggregateRoot @AggregateRoot} or {@link Entity @Entity} in the domain layer, a
   * type carrying {@link ValueObject @ValueObject} in the domain layer or in an {@code ..api..}
   * published-contract package. Each marks a model concept, so none of them belongs in the
   * application, infrastructure, or interface layers. Part of {@link AiPersimmonDddRules#all()};
   * matches nothing (and so passes) in a project that annotates no building blocks.
   *
   * <p><strong>Why a value object gets the extra package and an entity does not.</strong> A value
   * object is sometimes deliberately published — a multi-context layout has to put the identifier
   * one context exposes for others to hold, and a shared kernel's value types, in {@code ..api..},
   * which is where {@link BoundedContextRules#dependOnEachOtherOnlyThroughApi(String)} requires
   * anything another context may touch to live. Held to {@code ..domain..} alone, those two rules
   * had no package that satisfied both, and the way out was to drop the annotation from exactly the
   * types most exposed — which also dropped {@link #valueObjectsShouldBeImmutable()} on them. This
   * is the same distinction {@link EventRules} already draws between {@link
   * EventRules#domainEventsShouldStayInDomain()} and {@link
   * EventRules#integrationEventsShouldResideInApi()}: the internal fact stays in the domain, the
   * published one lives with the contract.
   *
   * <p>An aggregate root and an entity get no such allowance, because publishing one is not a thing
   * a bounded context should do: it has identity, a lifecycle and invariants, and exposing it makes
   * a shared model out of what contexts exist to keep separate. A published value object is a word
   * in the contract's vocabulary; a published entity is somebody else's aggregate.
   */
  public static ArchRule domainBuildingBlocksShouldResideInDomain() {
    return CompositeArchRule.of(aggregatesAndEntitiesShouldResideInDomain())
        .and(valueObjectsShouldResideInDomainOrApi())
        .as(
            "@AggregateRoot and @Entity types should reside in the domain layer, and @ValueObject "
                + "types in the domain layer or a published ..api.. package");
  }

  /**
   * The strict half of {@link #domainBuildingBlocksShouldResideInDomain()}: {@link
   * AggregateRoot @AggregateRoot} and {@link Entity @Entity} in {@code ..domain..}, with no
   * published exception. Exposed separately so a project can state that half on its own.
   */
  public static ArchRule aggregatesAndEntitiesShouldResideInDomain() {
    return classes()
        .that()
        .areAnnotatedWith(AggregateRoot.class)
        .or()
        .areAnnotatedWith(Entity.class)
        .should()
        .resideInAPackage("..domain..")
        .as("@AggregateRoot and @Entity types should reside in the domain layer")
        .because(
            "an aggregate root and an entity have identity, a lifecycle and invariants, so they "
                + "belong with the domain and are never part of a published contract")
        .allowEmptyShould(true);
  }

  /**
   * The other half of {@link #domainBuildingBlocksShouldResideInDomain()}: a {@link
   * ValueObject @ValueObject} resides in {@code ..domain..} or in an {@code ..api..}
   * published-contract package — never in application, infrastructure, or interface code. Exposed
   * separately so a project can state that half on its own.
   */
  public static ArchRule valueObjectsShouldResideInDomainOrApi() {
    return classes()
        .that()
        .areAnnotatedWith(ValueObject.class)
        .should()
        .resideInAnyPackage("..domain..", "..api..")
        .as("@ValueObject types should reside in the domain layer or a published ..api.. package")
        .because(
            "a value object is a model concept, so it belongs with the domain — or, when it is "
                + "deliberately published for other contexts to hold, with the outward contract")
        .allowEmptyShould(true);
  }

  /**
   * A domain service — a type carrying {@link Service @Service} — resides in the domain layer. It
   * is stateless domain behaviour that does not sit naturally on a single entity or value object,
   * so it belongs with the model rather than in an application, infrastructure, or interface
   * package. Matched by the core {@code @Service} annotation, not Spring's stereotype, so an
   * application component annotated with Spring's {@code @Service} is unaffected. Part of {@link
   * AiPersimmonDddRules#all()}; matches nothing (and so passes) in a project that declares no
   * domain services.
   */
  public static ArchRule domainServicesShouldResideInDomain() {
    return classes()
        .that()
        .areAnnotatedWith(Service.class)
        .should()
        .resideInAPackage("..domain..")
        .as("@Service (domain service) types should reside in the domain layer")
        .because("a domain service is stateless domain behaviour, so it belongs with the model")
        .allowEmptyShould(true);
  }

  /**
   * A type marked {@link AggregateRoot @AggregateRoot} extends {@link AbstractAggregateRoot}, so it
   * actually carries the aggregate lifecycle — recording domain events and enforcing invariants
   * through {@code checkInvariant} — rather than only claiming the role by annotation. Pairs with
   * {@link #domainBuildingBlocksShouldResideInDomain()}: that fixes the layer, this requires the
   * base class. Part of {@link AiPersimmonDddRules#all()}; matches nothing (and so passes) in a
   * project with no annotated aggregate roots.
   */
  public static ArchRule aggregateRootsShouldExtendAbstractAggregateRoot() {
    return classes()
        .that()
        .areAnnotatedWith(AggregateRoot.class)
        .should()
        .beAssignableTo(AbstractAggregateRoot.class)
        .as("@AggregateRoot types should extend AbstractAggregateRoot")
        .because(
            "an aggregate root records domain events and enforces invariants through the base "
                + "class, so the annotation and the lifecycle it implies must not drift apart")
        .allowEmptyShould(true);
  }

  /**
   * A value object — a type carrying {@link ValueObject @ValueObject} — has only final fields. A
   * value object is defined by its attributes and compared by their equality; letting a field be
   * reassigned would give it identity-like behaviour and break that contract. A {@code record}
   * satisfies this for free; a class must declare its fields {@code final}.
   *
   * <p>This checks field <em>reassignment</em> only — it is shallow. A {@code final List} field
   * whose list is externally mutable passes, because bytecode cannot show whether the constructor
   * defensively copied. Real immutability is the convention the scaffolds model: records whose
   * compact constructors take {@code List.copyOf}/{@code Map.copyOf} of every collection argument.
   * Part of {@link AiPersimmonDddRules#all()}; matches nothing (and so passes) in a project that
   * annotates no value objects.
   */
  public static ArchRule valueObjectsShouldBeImmutable() {
    return classes()
        .that()
        .areAnnotatedWith(ValueObject.class)
        .should()
        .haveOnlyFinalFields()
        .as("@ValueObject types should be immutable (have only final fields)")
        .because(
            "a value object is defined by its attributes and compared by their equality, so it "
                + "must not change after construction")
        .allowEmptyShould(true);
  }

  /**
   * Only persistence adapters advance the optimistic-lock witness: no class in the domain or
   * application layer calls {@link AbstractAggregateRoot#versionAdvanced()}.
   *
   * <p>The method is public out of necessity — the repository bases live in other packages — but
   * its one legitimate caller is the adapter that just performed a version-checked write. A domain
   * or application class calling it advances the witness without a write, which quietly disarms the
   * optimistic lock: the next save checks against a version the row never reached. Part of {@link
   * AiPersimmonDddRules#all()}; matches nothing (and so passes) in a project with no aggregates.
   */
  public static ArchRule versionWitnessIsAdvancedOnlyByPersistenceAdapters() {
    return classes()
        .that()
        .resideInAPackage("..domain..")
        .or()
        .resideInAPackage("..application..")
        .should(notCallVersionAdvanced())
        .as("domain and application classes should not call versionAdvanced()")
        .because(
            "advancing the optimistic-lock witness is the persistence adapter's acknowledgement "
                + "of a version-checked write; calling it anywhere else disarms the lock — the "
                + "next save checks against a version the row never reached")
        .allowEmptyShould(true);
  }

  private static ArchCondition<JavaClass> notCallVersionAdvanced() {
    return new ArchCondition<>("not call AbstractAggregateRoot.versionAdvanced()") {
      @Override
      public void check(JavaClass origin, ConditionEvents events) {
        origin
            .getMethodCallsFromSelf()
            .forEach(
                call -> {
                  boolean advancesWitness =
                      call.getTarget().getName().equals("versionAdvanced")
                          && call.getTarget()
                              .getOwner()
                              .isAssignableTo(AbstractAggregateRoot.class);
                  if (advancesWitness) {
                    events.add(SimpleConditionEvent.violated(call, call.getDescription()));
                  }
                });
      }
    };
  }
}
