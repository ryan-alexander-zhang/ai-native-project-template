package com.example.samples.s10.banking;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The layering rules, plus the two boundaries this service draws around Seata. */
@AnalyzeClasses(
    packages = "com.example.samples.s10.banking",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  /**
   * The aggregate does not know it is one branch of anything.
   *
   * <p>The rule this service exists to demonstrate. If {@code Account} imported Seata it would be admitting
   * that "the debit might be undone" is part of the model — and the moment that is in the model, someone adds
   * a {@code pending} flag to represent it, and the strong-consistency argument is gone.
   */
  @ArchTest
  static final ArchRule thedomainDoesNotKnowAboutTheCoordinator =
      noClasses()
          .that()
          .resideInAPackage("..banking.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.apache.seata..")
          .as("the account aggregate should not know a transaction coordinator exists")
          .allowEmptyShould(true);

  /**
   * The controller does not open the global transaction.
   *
   * <p>"These writes are one outcome" is a statement about a business transaction, so it belongs where the
   * business transaction is named — one layer down, in the application service. A {@code @GlobalTransactional}
   * on a controller method makes the transaction boundary a property of the URL, which means the next entry
   * point (a scheduled job, a message consumer, an admin tool) silently gets no transaction at all.
   */
  @ArchTest
  static final ArchRule theedgeDoesNotOwnTheTransactionBoundary =
      noClasses()
          .that()
          .resideInAPackage("..banking.web..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.apache.seata.spring.annotation..")
          .as("the global transaction boundary belongs to the use case, not to the endpoint")
          .allowEmptyShould(true);
}
