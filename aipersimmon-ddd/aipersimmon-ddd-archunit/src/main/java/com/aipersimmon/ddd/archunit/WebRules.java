package com.aipersimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.annotation.Entity;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Web-boundary rules: what an HTTP endpoint may put in its signature.
 *
 * <p>{@link #controllerSignaturesShouldNotExposeTheDomain()} is bundled into {@link
 * AiPersimmonDddRules#all()}. Spring's {@code @Controller} is matched by fully-qualified name — and
 * as a meta-annotation, which is how {@code @RestController} is recognised — so this jar keeps no
 * compile dependency on Spring and a non-Spring project passes vacuously.
 *
 * <p><strong>There is deliberately no rule about where a controller lives.</strong> The obvious
 * companion — "a controller resides in the interface layer" — was written and then dropped, because
 * this project's own code contains two well-argued counterexamples: the multi-module scaffold
 * mounts an operations endpoint in its composition root, where no bounded context owns it, and the
 * third-party-integration sample puts a provider callback endpoint inside its anticorruption
 * package with a paragraph explaining that a callback is the return path of an outbound call rather
 * than part of anyone's API. A rule that has to be suppressed on its first two encounters is not a
 * rule. What survives is the part neither counterexample disputes: whatever package the endpoint is
 * in, the model does not appear in its signature.
 */
public final class WebRules {

  /**
   * Spring's controller stereotype. {@code @RestController} carries it as a meta-annotation, so
   * matching this one name covers both.
   */
  private static final String SPRING_CONTROLLER = "org.springframework.stereotype.Controller";

  /**
   * Spring's request mapping. {@code @GetMapping} and friends carry it as a meta-annotation, so
   * this one name identifies every endpoint method.
   */
  private static final String SPRING_REQUEST_MAPPING =
      "org.springframework.web.bind.annotation.RequestMapping";

  private WebRules() {}

  /**
   * No parameter or return type of a controller method is an {@link AggregateRoot @AggregateRoot}
   * or {@link Entity @Entity} — including inside a generic signature, so {@code
   * ResponseEntity<Order>}, {@code List<Order>} and {@code Optional<Order>} are caught as readily
   * as a bare {@code Order}.
   *
   * <p>A method signature at the boundary <em>is</em> the wire contract: the return type is what
   * gets serialised, the parameter type is what gets bound. Putting the aggregate there publishes
   * the write model as the API, and the consequences run in both directions. Outward, every field
   * the root has is now a field a client can see and depend on, so renaming one inside the model is
   * a breaking API change, and any field the model holds for its own reasons leaks. Inward, binding
   * a request body straight onto the root lets the framework build one field by field, through no
   * factory and no invariant — the object exists in a state the aggregate's own constructors would
   * have refused.
   *
   * <p>The line is drawn at identity, not at the domain package, exactly as in {@link
   * CqrsRules#readModelsShouldBeProjectionShapes()}. A controller that takes a domain value object
   * in order to build a query — an identifier, a currency — is doing the boundary's job of turning
   * primitives into the context's vocabulary, and reporting that would push endpoints back to raw
   * strings, which is the opposite of the intent.
   *
   * <p>Part of {@link AiPersimmonDddRules#all()}; matches nothing (and so passes) in a project with
   * no Spring controllers.
   */
  public static ArchRule controllerSignaturesShouldNotExposeTheDomain() {
    return classes()
        .that(areControllers())
        .should(notNameAnAggregateOrEntityInAMethodSignature())
        .as("controller method signatures should not expose aggregates or entities")
        .because(
            "a boundary signature is the wire contract: an aggregate in the return type publishes "
                + "the write model as the API, and one in a parameter lets the framework build a "
                + "root field by field through no factory and no invariant")
        .allowEmptyShould(true);
  }

  /** A Spring controller: annotated {@code @Controller}, directly or as a meta-annotation. */
  private static DescribedPredicate<JavaClass> areControllers() {
    return DescribedPredicate.describe(
        "are Spring controllers",
        javaClass ->
            javaClass.isAnnotatedWith(SPRING_CONTROLLER)
                || javaClass.isMetaAnnotatedWith(SPRING_CONTROLLER));
  }

  /**
   * Reports a violation for each controller method whose parameters or return type involve an
   * {@code @AggregateRoot} or {@code @Entity}. Uses the generic signature rather than the erasure,
   * so a root wrapped in {@code ResponseEntity}, {@code List} or {@code Optional} is found.
   */
  private static ArchCondition<JavaClass> notNameAnAggregateOrEntityInAMethodSignature() {
    return new ArchCondition<>("not name an @AggregateRoot or @Entity in a method signature") {
      @Override
      public void check(JavaClass controller, ConditionEvents events) {
        for (JavaMethod method : controller.getMethods()) {
          if (!isEndpoint(method)) {
            continue;
          }
          for (JavaClass exposed : writeModelTypesIn(method)) {
            events.add(
                SimpleConditionEvent.violated(
                    method,
                    method.getFullName()
                        + " names the write model "
                        + exposed.getName()
                        + " in its signature — take a request DTO and answer with a @ReadModel"));
          }
        }
      }
    };
  }

  /**
   * Whether the method is an HTTP endpoint — carrying {@code @RequestMapping} directly, or one of
   * the shorthands ({@code @GetMapping}, {@code @PostMapping}, …) that are meta-annotated with it.
   *
   * <p>Restricting to endpoints is not a softening; it is what makes the rule mean what it says.
   * The first version checked every method of the controller class and reported six of this
   * project's samples — every one of them on a <em>private</em> {@code body(Order)} helper that
   * maps the aggregate into a response map. That helper is not the wire contract; it is the code
   * that keeps the aggregate <em>out</em> of the wire contract. A rule that reports the mitigation
   * instead of the problem teaches the wrong lesson twice: it is wrong, and the obvious way to
   * silence it is to inline the mapping into the endpoint, which is worse than what it started
   * from.
   */
  private static boolean isEndpoint(JavaMethod method) {
    return method.isAnnotatedWith(SPRING_REQUEST_MAPPING)
        || method.isMetaAnnotatedWith(SPRING_REQUEST_MAPPING);
  }

  /** Every {@code @AggregateRoot} / {@code @Entity} involved in the method's signature. */
  private static Set<JavaClass> writeModelTypesIn(JavaMethod method) {
    Set<JavaClass> involved = new LinkedHashSet<>(method.getReturnType().getAllInvolvedRawTypes());
    for (JavaType parameter : method.getParameterTypes()) {
      involved.addAll(parameter.getAllInvolvedRawTypes());
    }
    involved.removeIf(
        type -> !type.isAnnotatedWith(AggregateRoot.class) && !type.isAnnotatedWith(Entity.class));
    return involved;
  }
}
