package com.example.samples.s12.ordering;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.aipersimmon.ddd.archunit.AiPersimmonDddRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The layering rules, plus the two boundaries a read model makes easy to cross. */
@AnalyzeClasses(
    packages = "com.example.samples.s12.ordering",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule ddd = AiPersimmonDddRules.all();

  /**
   * The write model does not know a read model exists.
   *
   * <p>The first thing to go wrong with a projection is a {@code projectedAt} or a {@code listDirty} column
   * appearing on the aggregate — and once the write model tracks the state of a derived table, the derived
   * table has stopped being derived and cannot be thrown away.
   */
  @ArchTest
  static final ArchRule thewriteModelDoesNotKnowAboutTheReadModel =
      noClasses()
          .that()
          .resideInAPackage("..ordering.domain..")
          .should()
          .dependOnClassesThat()
          .haveSimpleNameStartingWith("OrderList")
          .as("the aggregate should not know its projection exists")
          .allowEmptyShould(true);

  /**
   * The projection does not write through the aggregate's port.
   *
   * <p>Maintaining a read model by loading an {@code Order} and saving it would put the projection on the
   * write path with the aggregate's version check, its events and its invariants — and a projection has no
   * business republishing domain events. {@code OrderFacts} exists so the read side can ask the write side a
   * question in a shape that cannot be mistaken for permission to change it.
   */
  @ArchTest
  static final ArchRule theprojectionDoesNotWriteThroughTheAggregate =
      noClasses()
          .that()
          .haveSimpleNameContaining("Projection")
          .should()
          .dependOnClassesThat()
          .haveSimpleName("Orders")
          .as("a projection reads facts; it does not save aggregates")
          .allowEmptyShould(true);
}
