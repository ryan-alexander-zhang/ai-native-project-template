package com.example.samples.s12.catalog;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The layering rules, plus the one that keeps the ownership boundary honest. */
@AnalyzeClasses(
    packages = "com.example.samples.s12.catalog",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  /**
   * The catalogue does not know who is reading its names.
   *
   * <p>The rule guards the direction of the whole design. A publisher that knows its consumers starts serving
   * them: an endpoint "for the order list", a batch export shaped like somebody else's screen, a flag saying
   * whose cache needs warming. Each is a small convenience and each moves ownership of the copy back here,
   * where it cannot be maintained.
   */
  @ArchTest
  static final ArchRule thecatalogueDoesNotKnowItsConsumers =
      noClasses()
          .that()
          .resideInAPackage("com.example.samples.s12.catalog..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.example.samples.s12.ordering..")
          .as("the publisher should not depend on any consumer")
          .allowEmptyShould(true);
}
