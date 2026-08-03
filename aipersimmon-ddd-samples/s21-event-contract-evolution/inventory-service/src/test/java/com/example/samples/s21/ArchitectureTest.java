package com.example.samples.s21;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.aipersimmon.ddd.archunit.EventRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The bundle, plus the rule that keeps every revision of a consumed contract in {@code ..api..}.
 *
 * <p>The second rule pays for itself here: a retired revision is a class nothing references except an
 * upcaster, so it is exactly the kind of class that drifts into whatever package the upcaster lives in.
 */
@AnalyzeClasses(
    packages = "com.example.samples.s21",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  @ArchTest static final ArchRule contracts = EventRules.integrationEventsShouldResideInApi();
}
