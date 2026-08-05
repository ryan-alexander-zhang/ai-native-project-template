package com.example.samples.s25;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Three rules, and they are what stops "we wrapped the legacy in an ACL" from being a sentence in a wiki.
 *
 * <p>The catalogue asks how legacy code gets wrapped rather than called from everywhere. The pattern name contributes
 * nothing to that; a rule that fails the build contributes all of it. Note that the library's {@code BoundedContextRules}
 * is <strong>not</strong> used here — the monolith is not a bounded context with a published contract, it is a thing being
 * dismantled, and pretending it has an {@code api} package would be modelling the wrong shape.
 */
@AnalyzeClasses(
    packages = "com.example.samples.s25",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  /**
   * <strong>Only the ACL may touch the monolith.</strong> The whole answer to "how does legacy code get wrapped", as one
   * rule.
   *
   * <p>Without it, the first thing that happens is a controller calling {@code LegacyOrderService} "just for now" because
   * it needs one field, and the second is a handler doing it. Then the count of ways into the monolith is unknown, and
   * every subsequent step of the migration has to start by finding them.
   */
  @ArchTest
  static final ArchRule onlyTheAclTouchesTheLegacy =
      noClasses()
          .that()
          .resideOutsideOfPackages("com.example.samples.s25.acl..", "com.example.samples.s25.legacy..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.samples.s25.legacy..")
          .as("the monolith has exactly one seam, and it is the ACL")
          .allowEmptyShould(true);

  /**
   * <strong>The monolith may not depend on the new context.</strong>
   *
   * <p>Which is why the delegating entry point lives in {@code acl} rather than as a shim inside
   * {@code LegacyOrderService}. A shim there would be the first thread of the new code growing back into the old, and the
   * second one would not be reviewed — at which point the monolith is no longer a thing that can be deleted.
   */
  @ArchTest
  static final ArchRule thelegacyDoesNotGrowNewRoots =
      noClasses()
          .that()
          .resideInAPackage("com.example.samples.s25.legacy..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.example.samples.s25.refunds..", "com.example.samples.s25.acl..")
          .as("the monolith is being dismantled, not extended")
          .allowEmptyShould(true);

  /**
   * <strong>The new domain cannot see the monolith, even through the ACL.</strong>
   *
   * <p>Stricter than the rule above and for a different reason: an aggregate that could read the monolith would have
   * invariants that depend on legacy SQL, which is the position the extraction exists to escape. The order's facts reach
   * {@code Refund.raise} as arguments, which is what makes {@code RefundTest} run in milliseconds with no database.
   */
  @ArchTest
  static final ArchRule thenewDomainCannotSeeTheLegacy =
      noClasses()
          .that()
          .resideInAPackage("com.example.samples.s25.refunds.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.example.samples.s25.legacy..", "com.example.samples.s25.acl..")
          .as("an aggregate whose invariants depend on legacy SQL has not been extracted")
          .allowEmptyShould(true);
}
