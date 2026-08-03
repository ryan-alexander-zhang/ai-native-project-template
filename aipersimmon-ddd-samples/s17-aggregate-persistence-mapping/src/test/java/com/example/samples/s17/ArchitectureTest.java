package com.example.samples.s17;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The layering rules. Note the domain names no MyBatis type: the mapping lives entirely in the
 * adapter, which is what keeps the model reusable behind a different store. */
@AnalyzeClasses(
    packages = "com.example.samples.s17",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();
}
