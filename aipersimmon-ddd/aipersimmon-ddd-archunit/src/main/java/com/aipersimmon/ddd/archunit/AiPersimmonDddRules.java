package com.aipersimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.application.DomainEventHandler;
import com.aipersimmon.ddd.core.event.DomainEvent;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.aipersimmon.ddd.core.rule.BusinessRule;
import com.aipersimmon.ddd.core.rule.BusinessRuleViolationException;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Reusable ArchUnit rules enforcing the DDD layering and building-block
 * conventions. They match layers by the package segment they live in
 * ({@code ..domain..}, {@code ..application..}, {@code ..infrastructure..},
 * {@code ..adapter..}), so they hold whether a layer is a sub-package (single
 * deployable) or its own module (multi-module build) — the segment is present
 * either way.
 *
 * <p>Wire them into a test and let ArchUnit scope which classes are analysed:
 *
 * <pre>{@code
 * @AnalyzeClasses(packages = "com.example.app")
 * class ArchitectureTest {
 *     @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();
 * }
 * }</pre>
 *
 * <p>Every rule tolerates an empty match, so a project that has not yet
 * introduced a given layer still passes rather than erroring.
 */
public final class AiPersimmonDddRules {

    /**
     * Packages considered technical frameworks that the domain layer must not
     * touch. This is a sensible default; a project may add its own rule for
     * frameworks specific to it.
     */
    private static final String[] FRAMEWORK_PACKAGES = {
            "org.springframework..",
            "jakarta.persistence..",
            "javax.persistence..",
            "org.hibernate..",
            "org.apache.ibatis..",
            "com.baomidou..",
            "com.fasterxml.jackson..",
    };

    /**
     * Spring's event-subscriber annotation, matched by fully-qualified name so the
     * event-listener placement rules stay free of a compile dependency on Spring:
     * a project that does not use Spring simply has no matching methods, and the
     * rule passes vacuously.
     */
    private static final String SPRING_EVENT_LISTENER = "org.springframework.context.event.EventListener";

    private AiPersimmonDddRules() {
    }

    /** The domain layer must not depend on the layers built on top of it. */
    public static ArchRule domainShouldNotDependOnOuterLayers() {
        return noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..application..", "..infrastructure..", "..adapter..")
                .as("domain classes should not depend on the application, infrastructure, or interface layers")
                .because("the domain layer must stay independent of the layers built on top of it")
                .allowEmptyShould(true);
    }

    /** The application layer must not depend on infrastructure or the interface layer. */
    public static ArchRule applicationShouldNotDependOnInfrastructureOrInterface() {
        return noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..infrastructure..", "..adapter..")
                .as("application classes should not depend on the infrastructure or interface layers")
                .because("use-case orchestration must depend inward on the domain only")
                .allowEmptyShould(true);
    }

    /** The domain layer must be free of technical frameworks. */
    public static ArchRule domainShouldBeFrameworkFree() {
        return noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORK_PACKAGES)
                .as("domain classes should not depend on technical frameworks")
                .because("the domain layer must be free of Spring, JPA, and other framework concerns")
                .allowEmptyShould(true);
    }

    /**
     * Stricter, <em>opt-in</em> rule: the interface/adapter layer must not depend on
     * the domain directly, driving use cases through the application layer instead.
     *
     * <p>Deliberately <strong>not</strong> part of {@link #all()}: it forbids
     * <em>every</em> adapter&#8594;domain reference, which some layouts legitimately
     * need — for example a project that keeps its persistence adapters (repository
     * implementations that map aggregates) in the same module as its inbound
     * adapters. Adopt it in projects that separate persistence adapters out and want
     * the tighter hexagonal discipline where every driving adapter goes through the
     * application layer, and add it to a test alongside {@link #all()}:
     *
     * <pre>{@code
     * @ArchTest static final ArchRule adapters = AiPersimmonDddRules.adapterShouldNotDependOnDomain();
     * }</pre>
     */
    public static ArchRule adapterShouldNotDependOnDomain() {
        return noClasses().that().resideInAPackage("..adapter..")
                .should().dependOnClassesThat().resideInAPackage("..domain..")
                .as("interface/adapter classes should not depend on the domain layer directly")
                .because("driving adapters should invoke use cases through the application layer, "
                        + "rather than reaching into domain internals")
                .allowEmptyShould(true);
    }

    /**
     * Domain events belong to the domain layer, not to the interface or integration
     * layers. Matches a type declared as a domain event <em>either</em> way the core
     * offers — implementing the {@link DomainEvent} marker interface or carrying the
     * {@link com.aipersimmon.ddd.core.annotation.DomainEvent @DomainEvent} annotation
     * — since both express the same role and the annotation path must be guarded too.
     */
    public static ArchRule domainEventsShouldStayInDomain() {
        return classes().that().implement(DomainEvent.class)
                .or().areAnnotatedWith(com.aipersimmon.ddd.core.annotation.DomainEvent.class)
                .should().resideInAPackage("..domain..")
                .as("domain events should reside in the domain layer")
                .because("a domain event is an internal fact of the bounded context, "
                        + "not a cross-context contract or a delivery concern")
                .allowEmptyShould(true);
    }

    /**
     * A subscriber of an in-process domain event (an {@code @EventListener} method
     * whose argument is a {@link DomainEvent}) resides in the application layer (or
     * the domain), never in an inbound adapter. A domain event is consumed within its
     * own bounded context; its subscriber orchestrates a use case or starts a process,
     * which is application (or domain) work — not the transport translation an adapter
     * does. Part of {@link #all()}; the rule matches nothing (and so passes) in a
     * project that has no such subscribers.
     */
    public static ArchRule domainEventListenersShouldResideInApplicationOrDomain() {
        return methods().that(areEventListenersHandling(DomainEvent.class))
                .should().beDeclaredInClassesThat().resideInAnyPackage("..application..", "..domain..")
                .as("domain-event @EventListener handlers should reside in the application or domain layer")
                .because("a domain event is consumed within its bounded context; its subscriber belongs to "
                        + "the application (or domain) layer, not to an inbound adapter")
                .allowEmptyShould(true);
    }

    /**
     * A subscriber of an integration event (an {@code @EventListener} method whose
     * argument is an {@link IntegrationEvent}) resides in the interface/adapter layer.
     * An integration event arrives from another context over a transport; the
     * subscriber is the inbound adapter that receives it at the boundary and
     * translates it into a command (or hands a correlation id to a process manager) —
     * it holds no orchestration or domain logic itself. Part of {@link #all()}; the
     * rule matches nothing (and so passes) in a project that has no such subscribers.
     */
    public static ArchRule integrationEventListenersShouldResideInAdapter() {
        return methods().that(areEventListenersHandling(IntegrationEvent.class))
                .should().beDeclaredInClassesThat().resideInAPackage("..adapter..")
                .as("integration-event @EventListener handlers should reside in the interface/adapter layer")
                .because("an integration event arrives over a transport at the boundary, so its subscriber is "
                        + "an inbound adapter that translates it and hands off inward")
                .allowEmptyShould(true);
    }

    /**
     * A subscriber of an in-process domain event (an {@code @EventListener} method
     * whose argument is a {@link DomainEvent}) is declared in a class annotated
     * {@link DomainEventHandler @DomainEventHandler}. This makes the subscriber's role
     * explicit and lets tools locate domain-event handlers by annotation rather than
     * by a naming or parameter-shape heuristic. Pairs with
     * {@link #domainEventListenersShouldResideInApplicationOrDomain()}: that fixes the
     * layer, this requires the marker. Part of {@link #all()}; matches nothing (and so
     * passes) in a project that has no such subscribers.
     */
    public static ArchRule domainEventListenersShouldBeAnnotatedWithDomainEventHandler() {
        return methods().that(areEventListenersHandling(DomainEvent.class))
                .should().beDeclaredInClassesThat().areAnnotatedWith(DomainEventHandler.class)
                .as("domain-event @EventListener handlers should be declared in a @DomainEventHandler class")
                .because("a domain-event subscriber is a first-class application concern, marked as such so "
                        + "its role is explicit and architecture tests can find it by annotation")
                .allowEmptyShould(true);
    }

    /**
     * A {@link CommandHandler} implementation must not depend on another
     * {@link CommandHandler} implementation. A command handler is an entry point on
     * the command bus, not an internal API: one handler invoking another either
     * bypasses the callee's {@code CommandInterceptor} chain (its transaction,
     * validation, logging) or, if routed back through the bus, nests transactions and
     * double-applies those concerns; it also blurs the unit-of-work boundary and
     * couples two use cases that should evolve independently. Reusable logic belongs
     * in a domain service or a non-handler application collaborator, injected into
     * both handlers — see {@code decision-00010}. Part of {@link #all()}; matches
     * nothing (and so passes) in a project that has no command handlers.
     */
    public static ArchRule commandHandlersShouldNotDependOnOtherCommandHandlers() {
        return classes().that().implement(CommandHandler.class)
                .should(notDependOnAnotherCommandHandler())
                .as("command handlers should not depend on other command handlers")
                .because("a CommandHandler is a command-bus entry point, not an internal API; reuse belongs "
                        + "in a domain service or a non-handler application collaborator, not in a "
                        + "handler-to-handler dependency")
                .allowEmptyShould(true);
    }

    /**
     * Reports a violation for each dependency whose target is a {@link CommandHandler}
     * implementation other than the {@code CommandHandler} interface itself and other
     * than the origin class. Excluding the interface keeps a handler's own
     * {@code implements CommandHandler} from counting; excluding the origin keeps a
     * self-reference from counting. Used with {@code classes().should(...)}, so a
     * {@code violated} event is a rule violation.
     */
    private static ArchCondition<JavaClass> notDependOnAnotherCommandHandler() {
        return new ArchCondition<>("not depend on another CommandHandler implementation") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                origin.getDirectDependenciesFromSelf().forEach(dependency -> {
                    JavaClass target = dependency.getTargetClass();
                    boolean anotherHandler = target.isAssignableTo(CommandHandler.class)
                            && !target.isEquivalentTo(CommandHandler.class)
                            && !target.getName().equals(origin.getName());
                    if (anotherHandler) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                });
            }
        };
    }

    /**
     * A {@link BusinessRule} — a business invariant expressed as an object — resides in
     * the domain layer. It captures domain policy, so it belongs with the model, not in
     * the application, infrastructure, or interface layers. Part of {@link #all()};
     * matches nothing (and so passes) in a project that defines no business rules.
     */
    public static ArchRule businessRulesShouldResideInDomain() {
        return classes().that().implement(BusinessRule.class)
                .should().resideInAPackage("..domain..")
                .as("BusinessRule implementations should reside in the domain layer")
                .because("a business rule is a domain invariant, so it belongs with the model")
                .allowEmptyShould(true);
    }

    /**
     * A {@link BusinessRuleViolationException} is raised only through
     * {@link AbstractAggregateRoot#checkRule(BusinessRule)} — never constructed directly
     * by application, domain, or adapter code. Routing every violation through
     * {@code checkRule} keeps invariant enforcement uniform and the rule object the single
     * source of the message and code. Part of {@link #all()}; matches nothing (and so
     * passes) in a project that never constructs it directly.
     */
    public static ArchRule businessRuleViolationsShouldOnlyComeFromCheckRule() {
        return noClasses().that().doNotHaveFullyQualifiedName(AbstractAggregateRoot.class.getName())
                .should().callConstructor(BusinessRuleViolationException.class, BusinessRule.class)
                .as("BusinessRuleViolationException should be raised only via AbstractAggregateRoot.checkRule")
                .because("routing every invariant violation through checkRule keeps enforcement uniform and "
                        + "the rule object the single source of its message and code")
                .allowEmptyShould(true);
    }

    /**
     * A {@link BusinessRule} is a plain domain object, not a Spring-managed bean: it must
     * not carry a Spring stereotype ({@code @Component} or a meta-annotation of it such as
     * {@code @Service}/{@code @Repository}). Matched by fully-qualified name so the rule
     * needs no compile dependency on Spring. Part of {@link #all()}; matches nothing (and
     * so passes) in a project whose rules are not Spring beans.
     */
    public static ArchRule businessRulesShouldNotBeSpringComponents() {
        return noClasses().that().implement(BusinessRule.class)
                .should().beAnnotatedWith("org.springframework.stereotype.Component")
                .orShould().beMetaAnnotatedWith("org.springframework.stereotype.Component")
                .as("BusinessRule implementations should not be Spring components")
                .because("a business rule is a plain domain object constructed where the invariant is "
                        + "checked, not a container-managed bean")
                .allowEmptyShould(true);
    }

    /**
     * A method that both carries Spring's {@code @EventListener} (matched by name, see
     * {@link #SPRING_EVENT_LISTENER}) and takes a parameter assignable to the given
     * event marker — i.e. an event subscriber for that kind of event.
     */
    private static DescribedPredicate<JavaMethod> areEventListenersHandling(Class<?> eventMarker) {
        return DescribedPredicate.describe(
                "@EventListener methods handling a " + eventMarker.getSimpleName(),
                method -> method.isAnnotatedWith(SPRING_EVENT_LISTENER)
                        && method.getRawParameterTypes().stream()
                                .anyMatch(parameter -> parameter.isAssignableTo(eventMarker)));
    }

    /** All of the above, combined into a single rule. */
    public static ArchRule all() {
        return CompositeArchRule.of(domainShouldNotDependOnOuterLayers())
                .and(applicationShouldNotDependOnInfrastructureOrInterface())
                .and(domainShouldBeFrameworkFree())
                .and(domainEventsShouldStayInDomain())
                .and(domainEventListenersShouldResideInApplicationOrDomain())
                .and(integrationEventListenersShouldResideInAdapter())
                .and(domainEventListenersShouldBeAnnotatedWithDomainEventHandler())
                .and(commandHandlersShouldNotDependOnOtherCommandHandlers())
                .and(businessRulesShouldResideInDomain())
                .and(businessRuleViolationsShouldOnlyComeFromCheckRule())
                .and(businessRulesShouldNotBeSpringComponents());
    }
}
