package com.example.samples.s27;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The layering rules, plus the two boundaries this scenario is most likely to erode. */
@AnalyzeClasses(
    packages = "com.example.samples.s27",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  /**
   * The domain has never heard of the logical delete.
   *
   * <p>The rule that stops the infrastructure switch from becoming a business rule. Once a domain class can read
   * {@code @TableLogic} — or the MyBatis-Plus annotations at all — the next step is a rule that branches on whether
   * a record is visible, which is a statement about the persistence layer wearing a business vocabulary. Stated over
   * the whole MyBatis-Plus annotation package rather than over the one annotation, because the erosion never starts
   * with the one you forbade.
   */
  @ArchTest
  static final ArchRule thedomainKnowsNothingAboutTheDeleteFlag =
      noClasses()
          .that()
          .resideInAPackage("..customer.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.baomidou..")
          .as("a hidden row is not a business state")
          .allowEmptyShould(true);

  /**
   * Only the erasure's own handler may read the outbox.
   *
   * <p>{@code OutboxQueue} is a deliberate exception to "business code does not inspect the transport", justified in
   * its own javadoc by the fact that the queue's contents are personal data. An exception that nobody fences becomes
   * the new normal, so this pins it to the one class that argued for it: any other handler that starts asking about
   * delivery has to come and change this rule, and read the argument while doing so.
   */
  @ArchTest
  static final ArchRule onlyTheErasureReadsTheOutbox =
      noClasses()
          .that()
          .resideInAPackage("..customer.application..")
          .and()
          .haveSimpleNameNotEndingWith("EraseCustomerHandler")
          .and()
          .haveSimpleNameNotEndingWith("OutboxQueue")
          .should()
          .dependOnClassesThat()
          .haveSimpleName("OutboxQueue")
          .as("erasure is the only use case with a reason to know what has not been delivered")
          .allowEmptyShould(true);
}
