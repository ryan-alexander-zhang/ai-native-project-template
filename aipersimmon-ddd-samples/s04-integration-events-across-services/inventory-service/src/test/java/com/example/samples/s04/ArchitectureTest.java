package com.example.samples.s04;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.aipersimmon.ddd.archunit.EventRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The bundle, plus the rule that puts a consumed contract where a reader can find it. The inbound
 * adapter's placement in {@code ..adapter..} is the bundle's own rule for integration-event
 * subscribers.
 */
@AnalyzeClasses(
    packages = "com.example.samples.s04",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  @ArchTest static final ArchRule contracts = EventRules.integrationEventsShouldResideInApi();
}
