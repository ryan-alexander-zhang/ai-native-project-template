package com.example;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Verifies the ordering context against the reusable DDD layering rules. ArchUnit
 * imports the context's compiled classes (every module is on this module's
 * classpath) and checks the rules over them.
 */
@AnalyzeClasses(packages = {"com.example.ordering", "com.example.inventory"})
class ArchitectureTest {

    @ArchTest
    static final ArchRule ddd = AiPersimmonDddRules.all();

    /**
     * Spring-specific companion to {@code all()}: every persistence adapter implementing a
     * domain {@code @Repository} port carries Spring's {@code @Repository} stereotype (not a
     * bare {@code @Component}), so it names its role and gets persistence-exception
     * translation. Opt-in because it presumes Spring, which the framework-free {@code all()}
     * bundle does not.
     */
    @ArchTest
    static final ArchRule repositoryImplementations =
            AiPersimmonDddRules.repositoryImplementationsShouldBeSpringRepositories();

    /**
     * Integration events — the facts each context publishes for others — live in that
     * context's {@code ..api..} package, its published contract. Opt-in because it presumes
     * the {@code ..api..} convention this layout uses.
     */
    @ArchTest
    static final ArchRule integrationEvents = AiPersimmonDddRules.integrationEventsShouldResideInApi();

    /**
     * The ordering and inventory contexts depend on each other only through their
     * {@code ..api..} packages, never by reaching into each other's domain, application,
     * infrastructure, or adapter internals. The {@code start} composition root, which
     * legitimately wires both contexts together, is not analysed (see {@code @AnalyzeClasses}).
     */
    @ArchTest
    static final ArchRule contextsAreIsolated =
            AiPersimmonDddRules.boundedContextsShouldOnlyDependOnEachOthersApi("com.example");

    /**
     * The ordering inbound adapter must not depend on the ordering domain. Inbound
     * adapters translate a transport (HTTP, a cross-context integration event) into a
     * command or query; a domain-event subscriber belongs in the application layer,
     * not here. See decision-00008-event-subscriber-layer-placement. (The reusable
     * {@code all()} bundle already keeps domain-event subscribers in application and
     * integration-event subscribers in the adapter; this stricter, project-specific
     * rule additionally forbids <em>any</em> ordering-adapter&#8594;ordering-domain
     * reference, which the separate {@code ordering-infrastructure} module makes
     * unnecessary.)
     */
    @ArchTest
    static final ArchRule orderingAdapterDoesNotDependOnDomain =
            noClasses().that().resideInAPackage("..ordering.adapter..")
                    .should().dependOnClassesThat().resideInAPackage("..ordering.domain..")
                    .as("the ordering inbound adapter must not depend on the ordering domain")
                    .allowEmptyShould(true);
}
