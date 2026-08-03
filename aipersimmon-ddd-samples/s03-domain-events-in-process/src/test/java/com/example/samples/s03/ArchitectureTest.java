package com.example.samples.s03;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Includes the two rules that govern subscribers: they must live in the application (or domain) layer,
 * and they must carry {@code @DomainEventHandler} so the architecture test can find them at all.
 */
@AnalyzeClasses(
    packages = "com.example.samples.s03",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();
}
