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
    void domainShouldBeFrameworkFree_passesForGood() {
        assertDoesNotThrow(() -> AiPersimmonDddRules.domainShouldBeFrameworkFree().check(GOOD));
    }

    @Test
    void all_passesForGood() {
        assertDoesNotThrow(() -> AiPersimmonDddRules.all().check(GOOD));
    }
}
