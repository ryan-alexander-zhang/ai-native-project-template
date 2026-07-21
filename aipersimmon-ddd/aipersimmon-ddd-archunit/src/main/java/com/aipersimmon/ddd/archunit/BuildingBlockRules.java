package com.aipersimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.annotation.Entity;
import com.aipersimmon.ddd.core.annotation.Service;
import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Tactical building-block rules: where the domain model's building blocks live and the shape they
 * must have — aggregate roots, entities, value objects, and domain services. All are bundled into
 * {@link AiPersimmonDddRules#all()}.
 */
public final class BuildingBlockRules {

  private BuildingBlockRules() {}

  /**
   * The tactical building blocks that make up an aggregate — a type carrying {@link
   * AggregateRoot @AggregateRoot}, {@link Entity @Entity}, or {@link ValueObject @ValueObject} —
   * reside in the domain layer. Each marks a model concept, so it belongs with the model, never in
   * the application, infrastructure, or interface layers. Part of {@link
   * AiPersimmonDddRules#all()}; matches nothing (and so passes) in a project that annotates no
   * building blocks.
   */
  public static ArchRule domainBuildingBlocksShouldResideInDomain() {
    return classes()
        .that()
        .areAnnotatedWith(AggregateRoot.class)
        .or()
        .areAnnotatedWith(Entity.class)
        .or()
        .areAnnotatedWith(ValueObject.class)
        .should()
        .resideInAPackage("..domain..")
        .as("@AggregateRoot, @Entity, and @ValueObject types should reside in the domain layer")
        .because(
            "aggregate roots, entities, and value objects are model concepts that belong with "
                + "the domain, not in the application, infrastructure, or interface layers")
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
   * A value object — a type carrying {@link ValueObject @ValueObject} — has only final fields, so
   * it cannot be mutated after construction. A value object is defined by its attributes and
   * compared by their equality; letting a field change would give it identity-like behaviour and
   * break that contract. A {@code record} satisfies this for free; a class must declare its fields
   * {@code final}. Part of {@link AiPersimmonDddRules#all()}; matches nothing (and so passes) in a
   * project that annotates no value objects.
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
}
