package com.aipersimmon.ddd.archunit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class AiPersimmonDddRulesTest {

    private static final JavaClasses GOOD = new ClassFileImporter()
            .importPackages("com.aipersimmon.ddd.archunit.fixture.good");
    private static final JavaClasses BAD = new ClassFileImporter()
            .importPackages("com.aipersimmon.ddd.archunit.fixture.bad");
    private static final JavaClasses ANNOTATED_EVENT_IN_ADAPTER = new ClassFileImporter()
            .importPackages("com.aipersimmon.ddd.archunit.fixture.annotated");

    @Test
    void domainShouldNotDependOnOuterLayers_passesForGood() {
        assertDoesNotThrow(() -> AiPersimmonDddRules.domainShouldNotDependOnOuterLayers().check(GOOD));
    }

    @Test
    void domainShouldNotDependOnOuterLayers_failsForBad() {
        assertThrows(AssertionError.class,
                () -> AiPersimmonDddRules.domainShouldNotDependOnOuterLayers().check(BAD));
    }

    @Test
    void domainEventsShouldStayInDomain_passesForGood() {
        assertDoesNotThrow(() -> AiPersimmonDddRules.domainEventsShouldStayInDomain().check(GOOD));
    }

    @Test
    void domainEventsShouldStayInDomain_failsForBad() {
        assertThrows(AssertionError.class,
                () -> AiPersimmonDddRules.domainEventsShouldStayInDomain().check(BAD));
    }

    @Test
    void domainEventsShouldStayInDomain_catchesAnnotatedEventOutsideDomain() {
        assertThrows(AssertionError.class,
                () -> AiPersimmonDddRules.domainEventsShouldStayInDomain().check(ANNOTATED_EVENT_IN_ADAPTER));
    }

    @Test
    void domainEventListenersShouldResideInApplicationOrDomain_passesForGood() {
        assertDoesNotThrow(
                () -> AiPersimmonDddRules.domainEventListenersShouldResideInApplicationOrDomain().check(GOOD));
    }

    @Test
    void domainEventListenersShouldResideInApplicationOrDomain_failsForBad() {
        assertThrows(AssertionError.class,
                () -> AiPersimmonDddRules.domainEventListenersShouldResideInApplicationOrDomain().check(BAD));
    }

    @Test
    void integrationEventListenersShouldResideInAdapter_passesForGood() {
        assertDoesNotThrow(
                () -> AiPersimmonDddRules.integrationEventListenersShouldResideInAdapter().check(GOOD));
    }

    @Test
    void integrationEventListenersShouldResideInAdapter_failsForBad() {
        assertThrows(AssertionError.class,
                () -> AiPersimmonDddRules.integrationEventListenersShouldResideInAdapter().check(BAD));
    }

    @Test
    void domainEventListenersShouldBeAnnotatedWithDomainEventHandler_passesForGood() {
        assertDoesNotThrow(() -> AiPersimmonDddRules
                .domainEventListenersShouldBeAnnotatedWithDomainEventHandler().check(GOOD));
    }

    @Test
    void domainEventListenersShouldBeAnnotatedWithDomainEventHandler_failsForBad() {
        assertThrows(AssertionError.class, () -> AiPersimmonDddRules
                .domainEventListenersShouldBeAnnotatedWithDomainEventHandler().check(BAD));
    }

    @Test
    void commandHandlersShouldNotDependOnOtherCommandHandlers_passesForGood() {
        assertDoesNotThrow(() -> AiPersimmonDddRules
                .commandHandlersShouldNotDependOnOtherCommandHandlers().check(GOOD));
    }

    @Test
    void commandHandlersShouldNotDependOnOtherCommandHandlers_failsForBad() {
        assertThrows(AssertionError.class, () -> AiPersimmonDddRules
                .commandHandlersShouldNotDependOnOtherCommandHandlers().check(BAD));
    }

    @Test
    void domainShouldBeFrameworkFree_passesForGood() {
        assertDoesNotThrow(() -> AiPersimmonDddRules.domainShouldBeFrameworkFree().check(GOOD));
    }

    @Test
    void adapterShouldNotDependOnDomain_passesForGood() {
        assertDoesNotThrow(() -> AiPersimmonDddRules.adapterShouldNotDependOnDomain().check(GOOD));
    }

    @Test
    void adapterShouldNotDependOnDomain_failsForBad() {
        assertThrows(AssertionError.class,
                () -> AiPersimmonDddRules.adapterShouldNotDependOnDomain().check(BAD));
    }

    @Test
    void businessRulesShouldResideInDomain_passesForGood() {
        assertDoesNotThrow(() -> AiPersimmonDddRules.businessRulesShouldResideInDomain().check(GOOD));
    }

    @Test
    void businessRulesShouldResideInDomain_failsForBad() {
        assertThrows(AssertionError.class,
                () -> AiPersimmonDddRules.businessRulesShouldResideInDomain().check(BAD));
    }

    @Test
    void businessRuleViolationsShouldOnlyComeFromCheckRule_passesForGood() {
        assertDoesNotThrow(
                () -> AiPersimmonDddRules.businessRuleViolationsShouldOnlyComeFromCheckRule().check(GOOD));
    }

    @Test
    void businessRuleViolationsShouldOnlyComeFromCheckRule_failsForBad() {
        assertThrows(AssertionError.class,
                () -> AiPersimmonDddRules.businessRuleViolationsShouldOnlyComeFromCheckRule().check(BAD));
    }

    @Test
    void businessRulesShouldNotBeSpringComponents_passesForGood() {
        assertDoesNotThrow(() -> AiPersimmonDddRules.businessRulesShouldNotBeSpringComponents().check(GOOD));
    }

    @Test
    void businessRulesShouldNotBeSpringComponents_failsForBad() {
        assertThrows(AssertionError.class,
                () -> AiPersimmonDddRules.businessRulesShouldNotBeSpringComponents().check(BAD));
    }

    @Test
    void all_passesForGood() {
        assertDoesNotThrow(() -> AiPersimmonDddRules.all().check(GOOD));
    }
}
