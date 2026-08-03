package com.example.samples.s06;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * The bundle, plus the rule this sample exists to keep: <strong>the domain does not make the call.</strong>
 *
 * <p>The port is declared in the application layer and implemented in infrastructure, so nothing in
 * {@code ..domain..} may reference either. Stated as a rule rather than a habit, because the shortcut is
 * always one line away and always looks reasonable at the time.
 */
@AnalyzeClasses(
    packages = "com.example.samples.s06",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  @ArchTest
  static final ArchRule theDomainNeverReachesTheRiskService =
      ArchRuleDefinition.noClasses()
          .that()
          .resideInAPackage("..ordering.domain..")
          .should()
          .dependOnClassesThat()
          .haveSimpleNameContaining("Risk")
          .because(
              "a domain model that makes a network call has tied its invariants to another service's"
                  + " uptime, and can no longer be tested without a stub");
}
