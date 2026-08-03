package com.example.samples.s05;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The bundle. There is no {@code EventRules} check here because this service declares no integration
 * event: it consumes somebody else's format and publishes nothing, so it has no {@code ..api..} package
 * to police.
 */
@AnalyzeClasses(
    packages = "com.example.samples.s05",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();
}
