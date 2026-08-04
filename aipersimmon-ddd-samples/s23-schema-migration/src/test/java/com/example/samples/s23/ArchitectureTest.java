package com.example.samples.s23;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.aipersimmon.ddd.archunit.BoundedContextRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The layering rules, the cross-context isolation rule, and the one boundary a shared database erases if
 * nobody watches it.
 */
@AnalyzeClasses(
    packages = "com.example.samples.s23",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  /**
   * The two contexts do not import each other, at all.
   *
   * <p>This is the opt-in rule the library leaves out of {@code all()} because it needs a parameter, and a
   * sample with two contexts in one deployable is precisely where it earns its keep. Sharing a datasource makes
   * the wrong thing easy: billing could load an {@code Order}, and the compiler would not mind. It is the
   * database that would mind, later, when somebody tries to split them.
   *
   * <p>Note that neither context exposes an {@code api} package to the other here — they do not integrate at
   * all in this sample, because integration is S4's and S24's subject. Ordering's {@code api} package exists
   * for its published events, and billing does not read them.
   *
   * <p>The {@code migration} package counts as a context to this rule, since a context is the first segment
   * under the base package. That is harmless and slightly instructive: the migration wiring depends on neither
   * context's internals, only on Flyway and the library, so the class that decides the order of everybody's
   * migrations knows nothing about anybody's model.
   */
  @ArchTest
  static final ArchRule contextsAreIsolated =
      BoundedContextRules.dependOnEachOtherOnlyThroughApi("com.example.samples.s23");

  /**
   * Migration wiring is not something the domain knows about.
   *
   * <p>The temptation a shared database creates is a domain class that reaches for Flyway — to check a version,
   * to decide whether a column exists yet, to branch on "have we migrated". Every one of those turns a schema
   * state into a business rule, and there is no migration that removes it afterwards.
   */
  @ArchTest
  static final ArchRule thedomainKnowsNothingAboutMigrations =
      noClasses()
          .that()
          .resideInAnyPackage("..ordering.domain..", "..billing.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.flywaydb..", "com.aipersimmon.ddd.flyway..")
          .as("a schema version is not a business rule")
          .allowEmptyShould(true);
}
