package com.example.samples.s11;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The layering rules, plus the one this sample is about. */
@AnalyzeClasses(
    packages = "com.example.samples.s11",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  /**
   * No entry adapter may reach the database directly.
   *
   * <p>This is the rule S11 exists to state. The pressure to break it is highest exactly here: a
   * scheduled job feels like plumbing rather than a use case, and "just run one UPDATE from the
   * scheduler" is a two-line change that skips the aggregate, its rule, its events and its version
   * predicate all at once. Every entry — HTTP, timer, operator — goes through the application layer.
   */
  @ArchTest
  static final ArchRule entryAdaptersDoNotTouchThePersistenceTier =
      noClasses()
          .that()
          .resideInAPackage("..interfaces..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..infrastructure..", "org.springframework.jdbc..", "com.baomidou..")
          .as("entry adapters should reach the domain through the application layer, not the database")
          .allowEmptyShould(true);
}
