package com.example.samples.s21;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.aipersimmon.ddd.archunit.EventRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The bundle, plus the rule that keeps the published contract where a reader can find it. The second
 * rule earns its keep in this sample: a revision is added by adding a class, and the rule is what
 * stops it being added next to the code that happens to construct it.
 */
@AnalyzeClasses(
    packages = "com.example.samples.s21",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  @ArchTest static final ArchRule contracts = EventRules.integrationEventsShouldResideInApi();
}
