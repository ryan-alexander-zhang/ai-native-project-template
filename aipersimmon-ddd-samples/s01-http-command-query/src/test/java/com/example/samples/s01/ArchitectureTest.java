package com.example.samples.s01;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.aipersimmon.ddd.archunit.CqrsRules;
import com.aipersimmon.ddd.archunit.RepositoryRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The library's layering and building-block rules, run over this sample's own code.
 *
 * <p>Not decoration: {@code versionWitnessIsAdvancedOnlyByPersistenceAdapters} inside {@code all()} is
 * the only thing stopping application code from calling {@code versionAdvanced()} and disarming the
 * optimistic lock.
 *
 * <p>Tests are excluded from the import on purpose — a test's in-memory fake of a repository port
 * lives next to the test that uses it, not in an {@code ..infrastructure..} package.
 */
@AnalyzeClasses(
    packages = "com.example.samples.s01",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  /** Opt-in: a repository implementation carries Spring's {@code @Repository}. */
  @ArchTest
  static final ArchRule springRepositories = RepositoryRules.implementationsShouldBeSpringRepositories();

  /** Opt-in: every reference-typed component of a command declares a constraint. */
  @ArchTest
  static final ArchRule commandsDeclareConstraints =
      CqrsRules.commandComponentsShouldDeclareValidationConstraints();
}
