package com.example.samples.s22.ordering;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The layering rules, plus the two boundaries an operations surface makes easy to cross. */
@AnalyzeClasses(
    packages = "com.example.samples.s22.ordering",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  /**
   * The domain does not know that delivery exists.
   *
   * <p>The first thing to go wrong in a service that has been operated for a while is a {@code published}
   * or {@code lastAttemptAt} column arriving on the aggregate — and once the write model tracks delivery,
   * replaying a message means saving an aggregate: the version check, the invariants, and new events. The
   * outbox keeps that bookkeeping in a row of its own so an operator can requeue a message without
   * touching business state, and this rule is what keeps that true.
   */
  @ArchTest
  static final ArchRule thedomainDoesNotKnowDeliveryExists =
      noClasses()
          .that()
          .resideInAPackage("..ordering.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.aipersimmon.ddd.outbox..")
          .as("the aggregate should not know how or whether its events are delivered")
          .allowEmptyShould(true);

  /**
   * An endpoint may render a dead letter; it may not requeue one on its own.
   *
   * <p>{@code DeadLetters} (read) is fine anywhere. {@code DeadLetterStore} is the mutating port, and a
   * controller calling it directly would put "may this be replayed, and what does replaying it mean" in a
   * class that has no way to say so. The rule names the store rather than the package, because the split
   * between the two ports is exactly the line worth enforcing.
   */
  @ArchTest
  static final ArchRule replayGoesThroughTheApplicationService =
      noClasses()
          .that()
          .resideInAPackage("..ordering.adapter..")
          .should()
          .dependOnClassesThat()
          .haveSimpleName("DeadLetterStore")
          .as("an operations endpoint requeues through the application, not through the store")
          .allowEmptyShould(true);
}
