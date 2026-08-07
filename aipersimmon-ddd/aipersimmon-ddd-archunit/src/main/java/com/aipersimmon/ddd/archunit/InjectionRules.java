package com.aipersimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;

/**
 * Wiring rules: a collaborator arrives through the constructor, never through a field or a setter.
 *
 * <p>{@link #dependenciesShouldBeConstructorInjected()} is bundled into {@link
 * AiPersimmonDddRules#all()}. Every annotation here is matched by fully-qualified <em>name</em>, so
 * this jar keeps no compile dependency on Spring or on the {@code jakarta.inject} API, and a
 * project that uses neither has no matching members and passes vacuously.
 */
public final class InjectionRules {

  private static final String AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";
  private static final String VALUE = "org.springframework.beans.factory.annotation.Value";
  private static final String INJECT = "jakarta.inject.Inject";
  private static final String RESOURCE = "jakarta.annotation.Resource";

  private InjectionRules() {}

  /**
   * No field and no setter is injected: no {@code @Autowired}, {@code @Value}, {@code @Inject} or
   * {@code @Resource} on a field or a method.
   *
   * <p>Constructor injection is what makes a class's dependencies part of its type. A
   * field-injected collaborator can be neither {@code final} nor supplied by a caller, so the class
   * has no constructor that produces a usable instance — it can only be built by a container, and
   * the one cheap unit test that runs the aggregate or the handler against a stub stops being
   * writable. The same absence hides growth: a constructor with eight parameters is visibly a class
   * doing too much, while eight annotated fields look exactly like three.
   *
   * <p>Field-level {@code @Value} is included for the same reason and one more: it is
   * configuration, and configuration read into a field cannot be supplied at construction, so a
   * test that wants a different timeout has to start a context to get one. On a <em>constructor
   * parameter</em> {@code @Value} is fine and is not matched — that is the sanctioned spelling, the
   * one the framework's own reference process definition uses.
   *
   * <p>Part of {@link AiPersimmonDddRules#all()}. Architecture tests conventionally exclude test
   * sources ({@code ImportOption.DoNotIncludeTests}), which is what keeps this off the
   * {@code @Autowired} fields that are idiomatic in a {@code @SpringBootTest}.
   */
  public static ArchRule dependenciesShouldBeConstructorInjected() {
    return CompositeArchRule.of(fieldsShouldNotBeInjected())
        .and(settersShouldNotBeInjected())
        .as("dependencies should be constructor-injected, not set on fields or through setters");
  }

  /** The field half of {@link #dependenciesShouldBeConstructorInjected()}. */
  public static ArchRule fieldsShouldNotBeInjected() {
    return noFields()
        .should()
        .beAnnotatedWith(AUTOWIRED)
        .orShould()
        .beAnnotatedWith(VALUE)
        .orShould()
        .beAnnotatedWith(INJECT)
        .orShould()
        .beAnnotatedWith(RESOURCE)
        .as("fields should not be injected")
        .because(
            "a field-injected collaborator cannot be final and cannot be passed in, so the class "
                + "has no constructor that yields a usable instance and can only be built by a "
                + "container — which is also what makes eight dependencies look like three")
        .allowEmptyShould(true);
  }

  /** The setter half of {@link #dependenciesShouldBeConstructorInjected()}. */
  public static ArchRule settersShouldNotBeInjected() {
    return noMethods()
        .should()
        .beAnnotatedWith(AUTOWIRED)
        .orShould()
        .beAnnotatedWith(INJECT)
        .orShould()
        .beAnnotatedWith(RESOURCE)
        .as("setters should not be injected")
        .because(
            "setter injection leaves a window in which the object exists without its "
                + "collaborators, so 'is this fully built?' becomes a question the type cannot "
                + "answer")
        .allowEmptyShould(true);
  }
}
