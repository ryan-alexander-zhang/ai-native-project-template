package com.example.samples.s09;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The layering rules, plus the two this sample is about. */
@AnalyzeClasses(
    packages = "com.example.samples.s09",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  /**
   * No aggregate knows it is being coordinated.
   *
   * <p>This is what keeps the participants reusable and the flow replaceable. A domain class that imported
   * the process manager would be one that could not be tested, or deployed, without a coordinator — and it
   * would start growing "awaiting" statuses to match the flow's steps.
   */
  @ArchTest
  static final ArchRule thedomainDoesNotKnowAboutTheCoordinator =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.aipersimmon.ddd.processmanager..", "..fulfilment..")
          .as("aggregates should not know a process manager exists")
          .allowEmptyShould(true);

  /**
   * The flow's definition does no I/O.
   *
   * <p>The library requires it — a definition must be a pure, deterministic decision object — and nothing
   * enforces it at runtime, so a rule here is the only thing standing between "pure" and "pure until
   * somebody needed one lookup". Repositories are the temptation: reading the order to find the seat class
   * would be one line and would make the flow unreplayable, untestable without a database, and
   * non-deterministic on retry.
   */
  @ArchTest
  static final ArchRule theflowDefinitionTouchesNothing =
      noClasses()
          .that()
          .resideInAPackage("..application.fulfilment..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..domain..",
              "..infrastructure..",
              "org.springframework.jdbc..",
              "com.baomidou..",
              "com.aipersimmon.ddd.processmanager.runtime..")
          .as("a process definition should be a pure decision: no repository, no runtime, no database")
          .allowEmptyShould(true);
}
