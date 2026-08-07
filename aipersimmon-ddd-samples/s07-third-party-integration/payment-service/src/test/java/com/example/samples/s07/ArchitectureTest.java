package com.example.samples.s07;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The layering rules, plus the two this sample is about. */
@AnalyzeClasses(
    packages = "com.example.samples.s07",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  /**
   * The anticorruption layer is a one-way membrane.
   *
   * <p>This is S7's rule. Everything that knows the provider exists — its HTTP client, its wire records,
   * its result codes, its signature scheme, its callback endpoint — lives in one package, and nothing
   * outside may name any of it. The domain and application layers are already covered by the library's
   * layering rules; what this adds is the {@code adapter} package, which is the one most likely to be
   * tempted (a controller that "just" reads {@code result_code} to show the customer something).
   *
   * <p>Most of it is enforced by the compiler as well: every class in that package is package-private, so
   * there is nothing to depend on. This rule is what notices the day somebody makes one of them public.
   */
  @ArchTest
  static final ArchRule nothingOutsideTheAnticorruptionLayerKnowsTheProvider =
      noClasses()
          .that()
          .resideOutsideOfPackage("..infrastructure.gateway..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..infrastructure.gateway..")
          .as("only the anticorruption layer may know the payment provider exists")
          .allowEmptyShould(true);

  /**
   * And the membrane is sealed rather than merely respected: no type in it is visible outside it.
   *
   * <p>Package-private is a stronger guarantee than a rule about dependencies, because it fails at compile
   * time in the editor of whoever is about to break it. The rule exists to keep it that way.
   */
  @ArchTest
  static final ArchRule theAnticorruptionLayerExposesNothing =
      noClasses()
          .that()
          .resideInAPackage("..infrastructure.gateway..")
          .and()
          .haveNameNotMatching(".*package-info")
          .should()
          .bePublic()
          .as("the anticorruption layer's types should stay package-private")
          .allowEmptyShould(true);

  /**
   * No entry adapter reaches the database directly — the same rule S11 states, and it holds here even
   * though this service's most complicated entry is a timer.
   */
  @ArchTest
  static final ArchRule entryAdaptersDoNotTouchThePersistenceTier =
      noClasses()
          .that()
          .resideInAPackage("..adapter..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..infrastructure..", "org.springframework.jdbc..", "com.baomidou..")
          .as("entry adapters should reach the domain through the application layer, not the database")
          .allowEmptyShould(true);
}
