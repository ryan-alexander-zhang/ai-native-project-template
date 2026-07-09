package com.example;

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
}
