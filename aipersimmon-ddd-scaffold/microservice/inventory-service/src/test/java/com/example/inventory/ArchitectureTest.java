package com.example.inventory;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Verifies the inventory service's layers against the reusable DDD layering rules. */
@AnalyzeClasses(packages = "com.example.inventory")
class ArchitectureTest {

    @ArchTest
    static final ArchRule ddd = AiPersimmonDddRules.all();
}
