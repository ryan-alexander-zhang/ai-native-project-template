package com.aipersimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.core.event.DomainEvent;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;

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
     * <p>Deliberately <strong>not</strong> part of {@link #all()}: some inbound
     * adapters legitimately subscribe to domain events (a messaging adapter that
     * reacts to an internal event and hands off to a process manager, for example),
     * which this rule would forbid. Adopt it in projects that want the tighter
     * hexagonal discipline where every driving adapter goes through the application
     * layer, and add it to a test alongside {@link #all()}:
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
     * Stricter, <em>opt-in</em> rule: a subscriber of an in-process domain event (an
     * {@code @EventListener} method whose argument is a {@link DomainEvent}) resides
     * in the application layer (or the domain), never in an inbound adapter. A domain
     * event is consumed within its own bounded context; its subscriber orchestrates a
     * use case or starts a process, which is application (or domain) work — not the
     * transport translation an adapter does.
     *
     * <p>Belongs to the same discipline as {@link #adapterShouldNotDependOnDomain()}
     * and is likewise excluded from {@link #all()}: adopt them together in a project
     * that keeps every domain-event subscription out of its adapters.
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
     * Stricter, <em>opt-in</em> rule: a subscriber of an integration event (an
     * {@code @EventListener} method whose argument is an {@link IntegrationEvent})
     * resides in the interface/adapter layer. An integration event arrives from
     * another context over a transport; the subscriber is the inbound adapter that
     * receives it at the boundary and translates it into a command (or hands a
     * correlation id to a process manager) — it holds no orchestration or domain
     * logic itself.
     *
     * <p>Deliberately <strong>not</strong> part of {@link #all()}: where the inbound
     * adapter is a distinct layer this holds, but a project may legitimately place the
     * subscription elsewhere (for example a single-package deployable with no separate
     * adapter layer). Adopt it where inbound adapters are their own layer.
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
                .and(domainEventsShouldStayInDomain());
    }
}
