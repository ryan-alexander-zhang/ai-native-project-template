package com.example.samples.s04;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.aipersimmon.ddd.archunit.EventRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The bundle plus the opt-in rule that matters most on a publishing service: an integration event is
 * a published contract, so it belongs in {@code ..api..} where a reader can find every fact this
 * service promises without reading the domain.
 */
@AnalyzeClasses(
    packages = "com.example.samples.s04",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  @ArchTest static final ArchRule contracts = EventRules.integrationEventsShouldResideInApi();
}
