package com.example.samples.s20;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The layering rules, plus one this sample cares about more than most. */
@AnalyzeClasses(
    packages = "com.example.samples.s20",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  /**
   * The read side must not touch the aggregate.
   *
   * <p>Nothing in the framework enforces this — a read path may legitimately load an aggregate for a
   * single-entity read, as {@code Query}'s own documentation says. For a <em>list</em> it is the wrong
   * default: rebuilding one aggregate per row runs constructors and invariants that rendering a row
   * never uses, and it is the reason list endpoints get slow in a way no index fixes. Stating it as a
   * rule here keeps a well-meaning refactor from reaching for {@code Orders} because it is nearby.
   */
  @ArchTest
  static final ArchRule theReadSideNeverLoadsAnAggregate =
      noClasses()
          .that()
          .haveSimpleName("MyBatisOrderQueries")
          .or()
          .haveSimpleName("OffsetPager")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("com.example.samples.s20.ordering.domain.Order")
          .as("the list's read side should not load aggregates");
}
