package com.example.samples.s18;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.aipersimmon.ddd.archunit.CqrsRules;
import com.aipersimmon.ddd.archunit.EventRules;
import com.aipersimmon.ddd.archunit.LayeringRules;
import com.aipersimmon.ddd.archunit.RepositoryRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Layer 0 — the tests that fail on code nobody exercised.
 *
 * <p>They are cheap, they run in under a second, and two of them protect guarantees no other test can:
 * {@code versionWitnessIsAdvancedOnlyByPersistenceAdapters} is the only thing stopping business code
 * from disarming the optimistic lock, and {@code domainShouldBeFrameworkFree} is the only thing keeping
 * the model portable.
 */
@AnalyzeClasses(
    packages = "com.example.samples.s18",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  /** Twenty-six rules. Adopt this first; the four below are separate decisions. */
  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  @ArchTest
  static final ArchRule adaptersDoNotReachIntoTheDomain = LayeringRules.adapterShouldNotDependOnDomain();

  @ArchTest
  static final ArchRule repositoryImplementationsAreSpringRepositories =
      RepositoryRules.implementationsShouldBeSpringRepositories();

  @ArchTest
  static final ArchRule commandsDeclareConstraints =
      CqrsRules.commandComponentsShouldDeclareValidationConstraints();

  @ArchTest
  static final ArchRule integrationEventsLiveInApi = EventRules.integrationEventsShouldResideInApi();
}
