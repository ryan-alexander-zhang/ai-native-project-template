package com.example.samples.s16;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The building-block rules over a module that is nothing but a domain.
 *
 * <p>Two of them are the reason this sample is worth compiling: {@code domainShouldBeFrameworkFree}
 * proves the claim in the POM — that the model needs no framework — and
 * {@code versionWitnessIsAdvancedOnlyByPersistenceAdapters} is what stops model code from calling
 * {@code versionAdvanced()} and quietly disarming the optimistic lock.
 */
@AnalyzeClasses(
    packages = "com.example.samples.s16",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();
}
