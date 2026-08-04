package com.example.samples.s10.points;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The layering rules, plus the one this service is about. */
@AnalyzeClasses(
    packages = "com.example.samples.s10.points",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  /**
   * The transaction protocol lives at the edge, and only at the edge.
   *
   * <p>This is the sample's central claim made enforceable. The participant's model holds {@code frozen}
   * because promised points are a fact about points; the moment a domain or application class imports Seata,
   * that stops being true and the column becomes protocol scaffolding instead. It would also mean the
   * aggregate could no longer be tested, or reused, without a coordinator — and {@code PointsAccountTest}
   * would need a Seata server to assert an arithmetic rule.
   */
  @ArchTest
  static final ArchRule onlyTheEdgeKnowsWhichTransactionProtocolThisIs =
      noClasses()
          .that()
          .resideInAnyPackage("..points.domain..", "..points.application..", "..points.infrastructure..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.apache.seata..")
          .as("only the web edge should know that Seata exists")
          .allowEmptyShould(true);
}
