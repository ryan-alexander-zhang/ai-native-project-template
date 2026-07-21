package com.aipersimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.aipersimmon.ddd.core.rule.Invariant;
import com.aipersimmon.ddd.core.rule.InvariantViolationException;
import com.aipersimmon.ddd.core.state.IllegalStateTransitionException;
import com.aipersimmon.ddd.core.state.Transitions;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Invariant, state-transition, and error-code rules: invariants are plain domain objects, invariant
 * and illegal-state-transition exceptions are raised only through their sanctioned call sites, and
 * error codes form a single enumerated catalogue. All are bundled into {@link
 * AiPersimmonDddRules#all()}.
 */
public final class InvariantAndErrorRules {

  private InvariantAndErrorRules() {}

  /**
   * An {@link Invariant} — a business invariant expressed as an object — resides in the domain
   * layer. It captures domain policy, so it belongs with the model, not in the application,
   * infrastructure, or interface layers. Part of {@link AiPersimmonDddRules#all()}; matches nothing
   * (and so passes) in a project that defines no invariants.
   */
  public static ArchRule invariantsShouldResideInDomain() {
    return classes()
        .that()
        .implement(Invariant.class)
        .should()
        .resideInAPackage("..domain..")
        .as("Invariant implementations should reside in the domain layer")
        .because("an invariant is a domain rule, so it belongs with the model")
        .allowEmptyShould(true);
  }

  /**
   * An {@link InvariantViolationException} is raised only through {@link
   * AbstractAggregateRoot#checkInvariant(Invariant)} — never constructed directly by application,
   * domain, or adapter code. Routing every violation through {@code checkInvariant} keeps invariant
   * enforcement uniform and the invariant object the single source of the message and code. Part of
   * {@link AiPersimmonDddRules#all()}; matches nothing (and so passes) in a project that never
   * constructs it directly.
   */
  public static ArchRule invariantViolationsShouldOnlyComeFromCheckInvariant() {
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName(AbstractAggregateRoot.class.getName())
        .should()
        .callConstructor(InvariantViolationException.class, Invariant.class)
        .as(
            "InvariantViolationException should be raised only via AbstractAggregateRoot.checkInvariant")
        .because(
            "routing every invariant violation through checkInvariant keeps enforcement uniform and "
                + "the invariant object the single source of its message and code")
        .allowEmptyShould(true);
  }

  /**
   * An {@link Invariant} is a plain domain object, not a Spring-managed bean: it must not carry a
   * Spring stereotype ({@code @Component} or a meta-annotation of it such as
   * {@code @Service}/{@code @Repository}). Matched by fully-qualified name so the rule needs no
   * compile dependency on Spring. Part of {@link AiPersimmonDddRules#all()}; matches nothing (and
   * so passes) in a project whose invariants are not Spring beans.
   */
  public static ArchRule invariantsShouldNotBeSpringComponents() {
    return noClasses()
        .that()
        .implement(Invariant.class)
        .should()
        .beAnnotatedWith("org.springframework.stereotype.Component")
        .orShould()
        .beMetaAnnotatedWith("org.springframework.stereotype.Component")
        .as("Invariant implementations should not be Spring components")
        .because(
            "an invariant is a plain domain object constructed where it is "
                + "checked, not a container-managed bean")
        .allowEmptyShould(true);
  }

  /**
   * An {@link IllegalStateTransitionException} is raised only from within {@link
   * Transitions#check(Object, Object)} — never constructed directly by domain, application, or
   * adapter code. Declaring the legal transitions in a {@link Transitions} table and routing every
   * check through it keeps the transition rules in one place and the exception's message uniform,
   * exactly as {@link #invariantViolationsShouldOnlyComeFromCheckInvariant()} does for invariants.
   * Guards the {@code (from, to)} constructor that {@code Transitions} uses; the {@link
   * ErrorCode}-carrying overload is a deliberate escape hatch and is not covered. Part of {@link
   * AiPersimmonDddRules#all()}; matches nothing (and so passes) in a project that never constructs
   * it directly.
   */
  public static ArchRule illegalStateTransitionsShouldOnlyComeFromTransitions() {
    return noClasses()
        .that()
        .doNotHaveFullyQualifiedName(Transitions.class.getName())
        .should()
        .callConstructor(IllegalStateTransitionException.class, Object.class, Object.class)
        .as("IllegalStateTransitionException should be raised only via Transitions.check")
        .because(
            "declaring the legal transitions in a Transitions table and routing every check "
                + "through it keeps the transition rules in one place and the exception uniform")
        .allowEmptyShould(true);
  }

  /**
   * An {@link ErrorCode} implementation is an enum, so a bounded context's error codes form one
   * enumerated catalogue in a single place, as the {@code ErrorCode} contract intends. Matches only
   * named (or anonymous) classes that declare {@code implements ErrorCode}; the inline {@code () ->
   * "code"} lambda shorthand used for a one-off code compiles to an {@code invokedynamic} target
   * with no such class and is not matched, so it stays allowed. Part of {@link
   * AiPersimmonDddRules#all()}; matches nothing (and so passes) in a project that declares no named
   * {@code ErrorCode} type.
   */
  public static ArchRule errorCodesShouldBeEnums() {
    return classes()
        .that()
        .implement(ErrorCode.class)
        .should()
        .beAssignableTo(Enum.class)
        .as("ErrorCode implementations should be enums")
        .because(
            "modelling a context's error codes as an enum keeps the catalogue in one place, "
                + "rather than scattering ad-hoc classes")
        .allowEmptyShould(true);
  }
}
