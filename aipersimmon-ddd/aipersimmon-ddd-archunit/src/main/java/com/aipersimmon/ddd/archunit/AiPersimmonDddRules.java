package com.aipersimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.core.event.DomainEvent;
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

    /** Domain events belong to the domain layer, not to the interface or integration layers. */
    public static ArchRule domainEventsShouldStayInDomain() {
        return classes().that().implement(DomainEvent.class)
                .should().resideInAPackage("..domain..")
                .as("domain events should reside in the domain layer")
                .because("a domain event is an internal fact of the bounded context, "
                        + "not a cross-context contract or a delivery concern")
                .allowEmptyShould(true);
    }

    /** All of the above, combined into a single rule. */
    public static ArchRule all() {
        return CompositeArchRule.of(domainShouldNotDependOnOuterLayers())
                .and(applicationShouldNotDependOnInfrastructureOrInterface())
                .and(domainShouldBeFrameworkFree())
                .and(domainEventsShouldStayInDomain());
    }
}
