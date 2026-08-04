package com.example.samples.s28;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The layering rules, plus the three boundaries this scenario is most likely to erode. */
@AnalyzeClasses(
    packages = "com.example.samples.s28",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  /**
   * The domain has never heard of a file.
   *
   * <p>{@code Artifact} carries a path as an opaque string, and that is where it stops. The pull the other way is
   * constant — a {@code Path} would be more type-safe, a {@code Files.size} would save a parameter — and each step
   * puts a streaming concern inside an invariant. Stated over {@code java.io} and {@code java.nio.file} together
   * because the erosion never starts with the one you forbade.
   */
  @ArchTest
  static final ArchRule thedomainKnowsNothingAboutFiles =
      noClasses()
          .that()
          .resideInAPackage("..reconciliation.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("java.io..", "java.nio.file..")
          .as("an artifact is a reference, not a file handle")
          .allowEmptyShould(true);

  /**
   * Only the export job's own mapper writes claim SQL.
   *
   * <p>Hand-written SQL over a table an aggregate repository also writes is a real exception to "writes go through the
   * aggregate", and it is justified at length in {@code ExportClaims} — a race N workers are meant to enter cannot be
   * a version-checked write. An exception nobody fences becomes the new normal, so this pins it: any other repository
   * that starts writing raw updates has to come and change this rule, and read the argument while doing so.
   */
  @ArchTest
  static final ArchRule onlyTheClaimIsHandWritten =
      noClasses()
          .that()
          .resideInAPackage("..reconciliation.infrastructure..")
          .and()
          .haveSimpleNameNotEndingWith("ExportJobMapper")
          .and()
          .haveSimpleNameNotEndingWith("ProgressMapper")
          .and()
          .haveSimpleNameNotEndingWith("ChunkReceiptMapper")
          .should()
          .beAnnotatedWith(org.apache.ibatis.annotations.Update.class)
          .as("the claim, the progress upsert and the chunk receipt are the only hand-written writes")
          .allowEmptyShould(true);

  /**
   * The process manager is on the classpath as a counterexample and is not used.
   *
   * <p>Which is worth a rule rather than a comment, because it is exactly the dependency somebody reaches for the next
   * time a flow needs a step — and the whole scenario is an argument for not doing that here.
   */
  @ArchTest
  static final ArchRule nothingInMainCodeUsesTheProcessManager =
      noClasses()
          .that()
          .resideInAPackage("com.example.samples.s28..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.aipersimmon.ddd.processmanager..")
          .as("this sample's whole point is that a job queue is not a process")
          .allowEmptyShould(true);
}
