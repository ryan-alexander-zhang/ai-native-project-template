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
     * The ordering inbound adapter must not depend on the ordering domain. Inbound
     * adapters translate a transport (HTTP, a cross-context integration event) into
     * a command or query; a domain-event subscriber belongs in the application
     * layer, not here. See decision-00008-event-subscriber-layer-placement. (The
     * reusable rules permit adapter→domain in general; this is a stricter,
     * project-specific choice enabled by keeping persistence adapters in a separate
     * {@code ordering-infrastructure} module.)
     */
    @ArchTest
    static final ArchRule orderingAdapterDoesNotDependOnDomain =
            noClasses().that().resideInAPackage("..ordering.adapter..")
                    .should().dependOnClassesThat().resideInAPackage("..ordering.domain..")
                    .as("the ordering inbound adapter must not depend on the ordering domain")
                    .allowEmptyShould(true);

    /**
     * Domain-event subscribers (such as {@code OrderFulfilmentStarter}) live in the
     * application layer, not in an inbound adapter. Opt-in rule that fixes the
     * placement decided in decision-00008-event-subscriber-layer-placement.
     */
    @ArchTest
    static final ArchRule domainEventListenersInApplication =
            AiPersimmonDddRules.domainEventListenersShouldResideInApplicationOrDomain();

    /**
     * Integration-event subscribers (such as {@code OrderFulfilment} and inventory's
     * {@code OrderPlacedListener}) live in the interface/adapter layer, per
     * decision-00008-event-subscriber-layer-placement.
     */
    @ArchTest
    static final ArchRule integrationEventListenersInAdapter =
            AiPersimmonDddRules.integrationEventListenersShouldResideInAdapter();
}
