package com.example;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Verifies both bounded contexts against the reusable DDD layering rules. In this
 * single-module modular monolith, the layer boundaries are packages, not separate
 * Maven modules, so this test is what enforces them: ArchUnit imports the compiled
 * classes and checks the rules over them.
 */
@AnalyzeClasses(packages = {"com.example.ordering", "com.example.inventory"})
class ArchitectureTest {

    @ArchTest
    static final ArchRule ddd = AiPersimmonDddRules.all();
}
