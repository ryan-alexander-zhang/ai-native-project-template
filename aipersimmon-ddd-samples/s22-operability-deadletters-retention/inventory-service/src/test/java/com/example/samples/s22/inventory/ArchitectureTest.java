package com.example.samples.s22.inventory;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The layering rules, plus the two the consuming side of an operability story depends on. */
@AnalyzeClasses(
    packages = "com.example.samples.s22.inventory",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  /**
   * No application class knows what the transport is.
   *
   * <p>The claim it protects is the one that makes the failure taxonomy tractable: retries, backoff and
   * dead-lettering are decided by one component that sees every delivery, and the only thing the
   * application contributes is the exception it throws. A listener that imported Kafka would start
   * handling its own retries — a {@code try/catch} with a counter, or an acknowledgement it decides
   * itself — and then two policies exist, one of them invisible.
   */
  @ArchTest
  static final ArchRule nothingInsideKnowsTheTransport =
      noClasses()
          .that()
          .resideInAnyPackage(
              "..inventory.adapter..",
              "..inventory.application..",
              "..inventory.domain..",
              "..inventory.infrastructure..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.apache.kafka..", "org.springframework.kafka..")
          .as("delivery is the transport's business; the application only throws")
          .allowEmptyShould(true);

  /**
   * The domain does not know that messages can arrive twice.
   *
   * <p>Deduplication is a property of the boundary, and an aggregate that knew about it would have a
   * {@code processedMessageIds} collection — which is the inbox, rebuilt inside the write model, with none
   * of its retention story and all of its growth.
   */
  @ArchTest
  static final ArchRule thedomainDoesNotKnowAboutTheInbox =
      noClasses()
          .that()
          .resideInAPackage("..inventory.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.aipersimmon.ddd.inbox..", "com.aipersimmon.ddd.outbox..")
          .as("dedup belongs at the boundary, not in the aggregate")
          .allowEmptyShould(true);
}
